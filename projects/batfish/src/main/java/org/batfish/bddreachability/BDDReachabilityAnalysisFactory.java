package org.batfish.bddreachability;

import static org.batfish.common.util.CommonUtil.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import net.sf.javabdd.BDD;
import org.batfish.common.util.CommonUtil;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ForwardingAnalysis;
import org.batfish.datamodel.IpSpace;
import org.batfish.datamodel.SourceNat;
import org.batfish.datamodel.UniverseIpSpace;
import org.batfish.specifier.InterfaceLinkLocation;
import org.batfish.specifier.InterfaceLocation;
import org.batfish.specifier.IpSpaceAssignment;
import org.batfish.specifier.Location;
import org.batfish.specifier.LocationVisitor;
import org.batfish.symbolic.bdd.BDDAcl;
import org.batfish.symbolic.bdd.BDDOps;
import org.batfish.symbolic.bdd.BDDPacket;
import org.batfish.symbolic.bdd.IpSpaceToBDD;
import org.batfish.z3.expr.StateExpr;
import org.batfish.z3.state.Accept;
import org.batfish.z3.state.Drop;
import org.batfish.z3.state.NeighborUnreachable;
import org.batfish.z3.state.NodeAccept;
import org.batfish.z3.state.NodeDrop;
import org.batfish.z3.state.NodeDropAclIn;
import org.batfish.z3.state.NodeDropAclOut;
import org.batfish.z3.state.NodeDropNoRoute;
import org.batfish.z3.state.NodeDropNullRoute;
import org.batfish.z3.state.NodeInterfaceNeighborUnreachable;
import org.batfish.z3.state.OriginateInterfaceLink;
import org.batfish.z3.state.OriginateVrf;
import org.batfish.z3.state.PostInVrf;
import org.batfish.z3.state.PreInInterface;
import org.batfish.z3.state.PreOutEdge;
import org.batfish.z3.state.PreOutEdgePostNat;
import org.batfish.z3.state.PreOutVrf;

public final class BDDReachabilityAnalysisFactory {
  private final Map<String, Map<String, BDD>> _aclDenyBDDs;
  private final Map<String, Map<String, BDD>> _aclPermitBDDs;
  private final Map<org.batfish.datamodel.Edge, BDD> _arpTrueEdgeBDDs;
  private final BDDOps _bddOps;
  private final BDDPacket _bddPacket;
  private final Map<StateExpr, Map<StateExpr, BDD>> _bddTransitions;
  private final Map<String, Configuration> _configs;
  private final Map<StateExpr, Map<StateExpr, Edge>> _edges;
  private final ForwardingAnalysis _forwardingAnalysis;
  private IpSpaceToBDD _ipSpaceToBDD;
  private final Map<String, Map<String, Map<String, BDD>>> _neighborUnreachableBDDs;
  private final BDD _nonDstIpVars;
  private final Map<String, Map<String, BDD>> _routableBDDs;
  private final Map<String, Map<String, BDD>> _vrfAcceptBDDs;
  private final Map<String, Map<String, BDD>> _vrfNotAcceptBDDs;

  public BDDReachabilityAnalysisFactory(
      Map<String, Configuration> configs,
      ForwardingAnalysis forwardingAnalysis,
      boolean dstIpOnly) {
    _bddOps = new BDDOps(BDDPacket.factory);
    _bddPacket = new BDDPacket();
    _configs = configs;
    _forwardingAnalysis = forwardingAnalysis;
    _ipSpaceToBDD = new IpSpaceToBDD(BDDPacket.factory, _bddPacket.getDstIp());

    BDDPacket pkt = new BDDPacket();
    _nonDstIpVars =
        dstIpOnly
            ? _bddOps.and(
                pkt.getTcpAck(),
                pkt.getTcpCwr(),
                pkt.getTcpEce(),
                pkt.getTcpFin(),
                pkt.getTcpPsh(),
                pkt.getTcpRst(),
                pkt.getTcpSyn(),
                _bddOps.and(pkt.getDstPort().getBitvec()),
                _bddOps.and(pkt.getIcmpCode().getBitvec()),
                _bddOps.and(pkt.getIcmpType().getBitvec()),
                _bddOps.and(pkt.getIpProtocol().getBitvec()),
                _bddOps.and(pkt.getSrcIp().getBitvec()),
                _bddOps.and(pkt.getSrcPort().getBitvec()))
            : null;

    Map<String, Map<String, BDDAcl>> bddAcls = computeBDDAcls(configs);
    _aclDenyBDDs = computeAclDenyBDDs(bddAcls, dstIpOnly);
    _aclPermitBDDs = computeAclPermitBDDs(bddAcls, dstIpOnly);

    _arpTrueEdgeBDDs = computeArpTrueEdgeBDDs(forwardingAnalysis, _ipSpaceToBDD);
    _neighborUnreachableBDDs = computeNeighborUnreachableBDDs(forwardingAnalysis, _ipSpaceToBDD);
    _routableBDDs = computeRoutableBDDs(forwardingAnalysis, _ipSpaceToBDD);
    _vrfAcceptBDDs = computeVrfAcceptBDDs(configs, _ipSpaceToBDD);
    _vrfNotAcceptBDDs = computeVrfNotAcceptBDDs(_vrfAcceptBDDs);

    _edges = computeEdges();
    _bddTransitions =
        toImmutableMap(
            _edges,
            Entry::getKey,
            preStateEntry ->
                toImmutableMap(
                    preStateEntry.getValue(),
                    Entry::getKey,
                    postStateEntry -> postStateEntry.getValue().getConstraint()));
  }

  private static Map<String, Map<String, BDDAcl>> computeBDDAcls(
      Map<String, Configuration> configs) {
    return toImmutableMap(
        configs,
        Entry::getKey,
        nodeEntry ->
            toImmutableMap(
                nodeEntry.getValue().getIpAccessLists(),
                Entry::getKey,
                aclEntry -> BDDAcl.create(aclEntry.getValue())));
  }

  private Map<String, Map<String, BDD>> computeAclDenyBDDs(
      Map<String, Map<String, BDDAcl>> aclBDDs, boolean dstIpOnly) {
    return toImmutableMap(
        aclBDDs,
        Entry::getKey,
        nodeEntry ->
            toImmutableMap(
                nodeEntry.getValue(),
                Entry::getKey,
                aclEntry -> {
                  BDD bdd = aclEntry.getValue().getBdd().not();
                  return dstIpOnly ? bdd.exist(_nonDstIpVars) : bdd;
                }));
  }

  private Map<String, Map<String, BDD>> computeAclPermitBDDs(
      Map<String, Map<String, BDDAcl>> aclBDDs, boolean dstIpOnly) {
    return toImmutableMap(
        aclBDDs,
        Entry::getKey,
        nodeEntry ->
            toImmutableMap(
                nodeEntry.getValue(),
                Entry::getKey,
                aclEntry -> {
                  BDD bdd = aclEntry.getValue().getBdd();
                  return dstIpOnly ? bdd.exist(_nonDstIpVars) : bdd;
                }));
  }

  private Map<StateExpr, Map<StateExpr, Edge>> computeEdges() {
    Map<StateExpr, Map<StateExpr, Edge>> edges = new HashMap<>();

    generateRules()
        .filter(edge -> !edge._constraint.isZero())
        .forEach(
            edge ->
                edges
                    .computeIfAbsent(edge._preState, k -> new HashMap<>())
                    .put(edge._postState, edge));

    // freeze
    return toImmutableMap(
        edges,
        Entry::getKey,
        preStateEntry -> toImmutableMap(preStateEntry.getValue(), Entry::getKey, Entry::getValue));
  }

  private static Map<String, Map<String, BDD>> computeRoutableBDDs(
      ForwardingAnalysis forwardingAnalysis, IpSpaceToBDD ipSpaceToBDD) {
    return toImmutableMap(
        forwardingAnalysis.getRoutableIps(),
        Entry::getKey,
        nodeEntry ->
            toImmutableMap(
                nodeEntry.getValue(),
                Entry::getKey,
                vrfEntry -> vrfEntry.getValue().accept(ipSpaceToBDD)));
  }

  private static Map<String, Map<String, BDD>> computeVrfNotAcceptBDDs(
      Map<String, Map<String, BDD>> vrfAcceptBDDs) {
    return toImmutableMap(
        vrfAcceptBDDs,
        Entry::getKey,
        nodeEntry ->
            toImmutableMap(
                nodeEntry.getValue(), Entry::getKey, vrfEntry -> vrfEntry.getValue().not()));
  }

  Map<StateExpr, Map<StateExpr, BDD>> getBDDTransitions() {
    return _bddTransitions;
  }

  IpSpaceToBDD getIpSpaceToBDD() {
    return _ipSpaceToBDD;
  }

  Map<String, Map<String, BDD>> getVrfAcceptBDDs() {
    return _vrfAcceptBDDs;
  }

  private static Map<org.batfish.datamodel.Edge, BDD> computeArpTrueEdgeBDDs(
      ForwardingAnalysis forwardingAnalysis, IpSpaceToBDD ipSpaceToBDD) {
    return toImmutableMap(
        forwardingAnalysis.getArpTrueEdge(),
        Entry::getKey,
        entry -> entry.getValue().accept(ipSpaceToBDD));
  }

  private static Map<String, Map<String, Map<String, BDD>>> computeNeighborUnreachableBDDs(
      ForwardingAnalysis forwardingAnalysis, IpSpaceToBDD ipSpaceToBDD) {
    return toImmutableMap(
        forwardingAnalysis.getNeighborUnreachable(),
        Entry::getKey,
        nodeEntry ->
            toImmutableMap(
                nodeEntry.getValue(),
                Entry::getKey,
                vrfEntry ->
                    toImmutableMap(
                        vrfEntry.getValue(),
                        Entry::getKey,
                        ifaceEntry -> ifaceEntry.getValue().accept(ipSpaceToBDD))));
  }

  private Stream<Edge> generateRules() {
    return Streams.concat(
        generateRules_NodeAccept_Accept(),
        generateRules_NodeDropAclIn_NodeDrop(),
        generateRules_NodeDropNoRoute_NodeDrop(),
        generateRules_NodeDropNullRoute_NodeDrop(),
        generateRules_NodeDropAclOut_NodeDrop(),
        generateRules_NodeDrop_Drop(),
        generateRules_NodeInterfaceNeighborUnreachable_NeighborUnreachable(),
        // generateRules_OriginateInterface_PreInInterface(),
        // generateRules_OriginateVrf_PostInVrf(),
        generateRules_PreInInterface_NodeDropAclIn(),
        generateRules_PreInInterface_PostInVrf(),
        generateRules_PostInVrf_NodeAccept(),
        generateRules_PostInVrf_NodeDropNoRoute(),
        generateRules_PostInVrf_PreOutVrf(),
        generateRules_PreOutEdge_PreOutEdgePostNat(),
        generateRules_PreOutEdgePostNat_NodeDropAclOut(),
        generateRules_PreOutEdgePostNat_PreInInterface(),
        generateRules_PreOutVrf_NodeDropNullRoute(),
        generateRules_PreOutVrf_NodeInterfaceNeighborUnreachable(),
        generateRules_PreOutVrf_PreOutEdge());
  }

  private Stream<Edge> generateRules_NodeAccept_Accept() {
    return _configs
        .keySet()
        .stream()
        .map(node -> new Edge(new NodeAccept(node), Accept.INSTANCE, BDDPacket.factory.one()));
  }

  private Stream<Edge> generateRules_NodeDropAclIn_NodeDrop() {
    return _configs
        .keySet()
        .stream()
        .map(
            node -> new Edge(new NodeDropAclIn(node), new NodeDrop(node), BDDPacket.factory.one()));
  }

  private Stream<Edge> generateRules_NodeDropAclOut_NodeDrop() {
    return _configs
        .keySet()
        .stream()
        .map(
            node ->
                new Edge(new NodeDropAclOut(node), new NodeDrop(node), BDDPacket.factory.one()));
  }

  private Stream<Edge> generateRules_NodeDropNoRoute_NodeDrop() {
    return _configs
        .keySet()
        .stream()
        .map(
            node ->
                new Edge(new NodeDropNoRoute(node), new NodeDrop(node), BDDPacket.factory.one()));
  }

  private Stream<Edge> generateRules_NodeDropNullRoute_NodeDrop() {
    return _configs
        .keySet()
        .stream()
        .map(
            node ->
                new Edge(new NodeDropNullRoute(node), new NodeDrop(node), BDDPacket.factory.one()));
  }

  private Stream<Edge> generateRules_NodeDrop_Drop() {
    return _configs
        .keySet()
        .stream()
        .map(node -> new Edge(new NodeDrop(node), Drop.INSTANCE, BDDPacket.factory.one()));
  }

  private Stream<Edge> generateRules_NodeInterfaceNeighborUnreachable_NeighborUnreachable() {
    return _configs
        .values()
        .stream()
        .flatMap(c -> c.getInterfaces().values().stream())
        .map(
            iface -> {
              String nodeNode = iface.getOwner().getHostname();
              String ifaceName = iface.getName();
              return new Edge(
                  new NodeInterfaceNeighborUnreachable(nodeNode, ifaceName),
                  NeighborUnreachable.INSTANCE,
                  BDDPacket.factory.one());
            });
  }

  private Stream<Edge> generateRules_OriginateInterface_PreInInterface() {
    return _configs
        .values()
        .stream()
        .map(Configuration::getInterfaces)
        .map(Map::values)
        .flatMap(Collection::stream)
        .map(
            iface -> {
              String hostname = iface.getOwner().getHostname();
              String name = iface.getName();
              return new Edge(
                  new OriginateInterfaceLink(hostname, name),
                  new PreInInterface(hostname, name),
                  BDDPacket.factory.one());
            });
  }

  private Stream<Edge> generateRules_OriginateVrf_PostInVrf() {
    return _configs
        .values()
        .stream()
        .flatMap(
            config -> {
              String hostname = config.getHostname();
              return config
                  .getVrfs()
                  .values()
                  .stream()
                  .map(
                      vrf -> {
                        String vrfName = vrf.getName();
                        return new Edge(
                            new OriginateVrf(hostname, vrfName),
                            new PostInVrf(hostname, vrfName),
                            BDDPacket.factory.one());
                      });
            });
  }

  private Stream<Edge> generateRules_PostInVrf_NodeAccept() {
    return _vrfAcceptBDDs
        .entrySet()
        .stream()
        .flatMap(
            nodeEntry ->
                nodeEntry
                    .getValue()
                    .entrySet()
                    .stream()
                    .map(
                        vrfEntry -> {
                          String node = nodeEntry.getKey();
                          String vrf = vrfEntry.getKey();
                          BDD acceptBDD = vrfEntry.getValue();
                          return new Edge(
                              new PostInVrf(node, vrf), new NodeAccept(node), acceptBDD);
                        }));
  }

  private Stream<Edge> generateRules_PostInVrf_NodeDropNoRoute() {
    return _vrfNotAcceptBDDs
        .entrySet()
        .stream()
        .flatMap(
            nodeEntry ->
                nodeEntry
                    .getValue()
                    .entrySet()
                    .stream()
                    .map(
                        vrfEntry -> {
                          String node = nodeEntry.getKey();
                          String vrf = vrfEntry.getKey();
                          BDD notAcceptBDD = vrfEntry.getValue();
                          BDD notRoutableBDD = _routableBDDs.get(node).get(vrf).not();
                          return new Edge(
                              new PostInVrf(node, vrf),
                              new NodeDropNoRoute(node),
                              notAcceptBDD.and(notRoutableBDD));
                        }));
  }

  private Stream<Edge> generateRules_PostInVrf_PreOutVrf() {
    return _vrfNotAcceptBDDs
        .entrySet()
        .stream()
        .flatMap(
            nodeEntry ->
                nodeEntry
                    .getValue()
                    .entrySet()
                    .stream()
                    .map(
                        vrfEntry -> {
                          String node = nodeEntry.getKey();
                          String vrf = vrfEntry.getKey();
                          BDD notAcceptBDD = vrfEntry.getValue();
                          BDD routableBDD = _routableBDDs.get(node).get(vrf);
                          return new Edge(
                              new PostInVrf(node, vrf),
                              new PreOutVrf(node, vrf),
                              notAcceptBDD.and(routableBDD));
                        }));
  }

  private Stream<Edge> generateRules_PreInInterface_NodeDropAclIn() {
    return _configs
        .values()
        .stream()
        .map(Configuration::getVrfs)
        .map(Map::values)
        .flatMap(Collection::stream)
        .flatMap(vrf -> vrf.getInterfaces().values().stream())
        .filter(iface -> iface.getIncomingFilter() != null)
        .map(
            iface -> {
              String aclName = iface.getIncomingFilterName();
              String nodeName = iface.getOwner().getName();
              String ifaceName = iface.getName();

              BDD aclDenyBDD = _aclDenyBDDs.get(nodeName).get(aclName);
              return new Edge(
                  new PreInInterface(nodeName, ifaceName), new NodeDropAclIn(nodeName), aclDenyBDD);
            });
  }

  private Stream<Edge> generateRules_PreInInterface_PostInVrf() {
    return _configs
        .values()
        .stream()
        .map(Configuration::getVrfs)
        .map(Map::values)
        .flatMap(Collection::stream)
        .flatMap(vrf -> vrf.getInterfaces().values().stream())
        .map(
            iface -> {
              String aclName = iface.getIncomingFilterName();
              String nodeName = iface.getOwner().getName();
              String vrfName = iface.getVrfName();
              String ifaceName = iface.getName();

              BDD inAclBDD =
                  aclName == null
                      ? BDDPacket.factory.one()
                      : _aclPermitBDDs.get(nodeName).get(aclName);
              return new Edge(
                  new PreInInterface(nodeName, ifaceName),
                  new PostInVrf(nodeName, vrfName),
                  inAclBDD);
            });
  }

  private Stream<Edge> generateRules_PreOutEdge_PreOutEdgePostNat() {
    return _forwardingAnalysis
        .getArpTrueEdge()
        .keySet()
        .stream()
        .map(
            edge -> {
              String node1 = edge.getNode1();
              String iface1 = edge.getInt1();
              String node2 = edge.getNode2();
              String iface2 = edge.getInt2();

              PreOutEdge preOutEdge = new PreOutEdge(node1, iface1, node2, iface2);
              PreOutEdgePostNat preOutEdgePostNat =
                  new PreOutEdgePostNat(node1, iface1, node2, iface2);

              List<SourceNat> sourceNats =
                  _configs.get(node1).getInterfaces().get(iface1).getSourceNats();

              List<BDDSourceNat> bddSourceNats = null;
              if (sourceNats != null) {
                ImmutableList.Builder<BDDSourceNat> bddSourceNatBuilder = ImmutableList.builder();
                for (SourceNat sourceNat : sourceNats) {
                  String aclName = sourceNat.getAcl().getName();
                  BDD match = _aclPermitBDDs.get(node1).get(aclName);
                  BDD setSrcIp =
                      _bddPacket
                          .getSrcIp()
                          .geq(sourceNat.getPoolIpFirst().asLong())
                          .and(_bddPacket.getSrcIp().leq(sourceNat.getPoolIpLast().asLong()));
                  bddSourceNatBuilder.add(new BDDSourceNat(match, setSrcIp));
                }
                bddSourceNats = bddSourceNatBuilder.build();
              }

              return new Edge(preOutEdge, preOutEdgePostNat, bddSourceNats);
            });
  }

  private Stream<Edge> generateRules_PreOutEdgePostNat_NodeDropAclOut() {
    return _forwardingAnalysis
        .getArpTrueEdge()
        .keySet()
        .stream()
        .flatMap(
            edge -> {
              String node1 = edge.getNode1();
              String iface1 = edge.getInt1();
              String node2 = edge.getNode2();
              String iface2 = edge.getInt2();

              String aclName =
                  _configs.get(node1).getInterfaces().get(iface1).getOutgoingFilterName();
              BDD aclDenyBDD = _aclDenyBDDs.get(node1).get(aclName);

              return aclDenyBDD != null
                  ? Stream.of(
                      new Edge(
                          new PreOutEdgePostNat(node1, iface1, node2, iface2),
                          new NodeDropAclOut(node1),
                          aclDenyBDD))
                  : Stream.of();
            });
  }

  private Stream<Edge> generateRules_PreOutEdgePostNat_PreInInterface() {
    return _forwardingAnalysis
        .getArpTrueEdge()
        .keySet()
        .stream()
        .map(
            edge -> {
              String node1 = edge.getNode1();
              String iface1 = edge.getInt1();
              String node2 = edge.getNode2();
              String iface2 = edge.getInt2();

              String aclName =
                  _configs.get(node1).getInterfaces().get(iface1).getOutgoingFilterName();
              BDD aclPermitBDD =
                  aclName == null
                      ? BDDPacket.factory.one()
                      : _aclPermitBDDs.get(node1).get(aclName);
              assert aclPermitBDD != null;

              return new Edge(
                  new PreOutEdgePostNat(node1, iface1, node2, iface2),
                  new PreInInterface(node2, iface2),
                  aclPermitBDD);
            });
  }

  private Stream<Edge> generateRules_PreOutVrf_NodeDropNullRoute() {
    return _forwardingAnalysis
        .getNullRoutedIps()
        .entrySet()
        .stream()
        .flatMap(
            nodeEntry ->
                nodeEntry
                    .getValue()
                    .entrySet()
                    .stream()
                    .map(
                        vrfEntry -> {
                          String node = nodeEntry.getKey();
                          String vrf = vrfEntry.getKey();
                          BDD nullRoutedBDD = vrfEntry.getValue().accept(_ipSpaceToBDD);
                          return new Edge(
                              new PreOutVrf(node, vrf), new NodeDropNullRoute(node), nullRoutedBDD);
                        }));
  }

  private Stream<Edge> generateRules_PreOutVrf_NodeInterfaceNeighborUnreachable() {
    return _neighborUnreachableBDDs
        .entrySet()
        .stream()
        .flatMap(
            nodeEntry -> {
              String node = nodeEntry.getKey();
              return nodeEntry
                  .getValue()
                  .entrySet()
                  .stream()
                  .flatMap(
                      vrfEntry -> {
                        String vrf = vrfEntry.getKey();
                        return vrfEntry
                            .getValue()
                            .entrySet()
                            .stream()
                            .map(
                                ifaceEntry -> {
                                  String iface = ifaceEntry.getKey();
                                  BDD ipSpaceBDD = ifaceEntry.getValue();
                                  String outAcl =
                                      _configs
                                          .get(node)
                                          .getInterfaces()
                                          .get(iface)
                                          .getOutgoingFilterName();
                                  BDD outAclBDD =
                                      outAcl == null
                                          ? BDDPacket.factory.one()
                                          : _aclPermitBDDs.get(node).get(outAcl);
                                  return new Edge(
                                      new PreOutVrf(node, vrf),
                                      new NodeInterfaceNeighborUnreachable(node, iface),
                                      ipSpaceBDD.and(outAclBDD));
                                });
                      });
            });
  }

  private Stream<Edge> generateRules_PreOutVrf_PreOutEdge() {
    return _arpTrueEdgeBDDs
        .entrySet()
        .stream()
        .map(
            entry -> {
              org.batfish.datamodel.Edge edge = entry.getKey();
              BDD arpTrue = entry.getValue();

              String node1 = edge.getNode1();
              String iface1 = edge.getInt1();
              String vrf1 = ifaceVrf(edge.getNode1(), edge.getInt1());
              String node2 = edge.getNode2();
              String iface2 = edge.getInt2();

              return new Edge(
                  new PreOutVrf(node1, vrf1),
                  new PreOutEdge(node1, iface1, node2, iface2),
                  arpTrue);
            });
  }

  @Nonnull
  private LocationVisitor<StateExpr> getLocationToStateExpr() {
    return new LocationVisitor<StateExpr>() {
      @Override
      public StateExpr visitInterfaceLinkLocation(
          @Nonnull InterfaceLinkLocation interfaceLinkLocation) {
        return new OriginateInterfaceLink(
            interfaceLinkLocation.getNodeName(), interfaceLinkLocation.getInterfaceName());
      }

      @Override
      public StateExpr visitInterfaceLocation(@Nonnull InterfaceLocation interfaceLocation) {
        String vrf =
            _configs
                .get(interfaceLocation.getNodeName())
                .getInterfaces()
                .get(interfaceLocation.getInterfaceName())
                .getVrf()
                .getName();
        return new OriginateVrf(interfaceLocation.getNodeName(), vrf);
      }
    };
  }

  public BDDReachabilityAnalysis bddReachabilityAnalysis(IpSpaceAssignment srcIpSpaceAssignment) {
    return bddReachabilityAnalysis(srcIpSpaceAssignment, UniverseIpSpace.INSTANCE);
  }

  public BDDReachabilityAnalysis bddReachabilityAnalysis(
      IpSpaceAssignment srcIpSpaceAssignment, IpSpace dstIpSpace) {
    Map<StateExpr, BDD> roots = new HashMap<>();
    BDDPacket pkt = new BDDPacket();
    IpSpaceToBDD srcIpSpaceToBDD = new IpSpaceToBDD(BDDPacket.factory, pkt.getSrcIp());
    IpSpaceToBDD dstIpSpaceToBDD = new IpSpaceToBDD(BDDPacket.factory, pkt.getDstIp());
    BDD dstIpSpaceBDD = dstIpSpace.accept(dstIpSpaceToBDD);

    for (IpSpaceAssignment.Entry entry : srcIpSpaceAssignment.getEntries()) {
      BDD srcIpSpaceBDD = entry.getIpSpace().accept(srcIpSpaceToBDD);
      BDD headerspaceBDD = srcIpSpaceBDD.and(dstIpSpaceBDD);
      for (Location loc : entry.getLocations()) {
        StateExpr root = loc.accept(getLocationToStateExpr());
        roots.put(root, headerspaceBDD);
      }
    }

    return new BDDReachabilityAnalysis(roots, _edges);
  }

  private String ifaceVrf(String node, String iface) {
    return _configs.get(node).getInterfaces().get(iface).getVrfName();
  }

  private static Map<String, Map<String, BDD>> computeVrfAcceptBDDs(
      Map<String, Configuration> configs, IpSpaceToBDD ipSpaceToBDD) {
    Map<String, Map<String, IpSpace>> vrfOwnedIpSpaces =
        CommonUtil.computeVrfOwnedIpSpaces(
            CommonUtil.computeIpVrfOwners(false, CommonUtil.computeNodeInterfaces(configs)));

    return CommonUtil.toImmutableMap(
        vrfOwnedIpSpaces,
        Entry::getKey,
        nodeEntry ->
            CommonUtil.toImmutableMap(
                nodeEntry.getValue(),
                Entry::getKey,
                vrfEntry -> vrfEntry.getValue().accept(ipSpaceToBDD)));
  }
}
