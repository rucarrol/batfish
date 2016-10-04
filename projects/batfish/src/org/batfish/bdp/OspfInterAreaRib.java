package org.batfish.bdp;

import org.batfish.datamodel.OspfInterAreaRoute;

public class OspfInterAreaRib extends AbstractRib<OspfInterAreaRoute> {

   /**
    *
    */
   private static final long serialVersionUID = 1L;

   @Override
   public int comparePreference(OspfInterAreaRoute lhs,
         OspfInterAreaRoute rhs) {
      // reversed on purpose
      return Integer.compare(rhs.getMetric(), lhs.getMetric());
   }

}
