package org.batfish.datamodel.answers;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import org.batfish.common.BatfishLogger;
import org.batfish.datamodel.questions.DisplayHints;
import org.batfish.datamodel.table.ColumnMetadata;
import org.batfish.datamodel.table.Row;
import org.batfish.datamodel.table.TableAnswerElement;
import org.batfish.datamodel.table.TableMetadata;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AnswerMetadataUtilTest {

  @Rule public ExpectedException _thrown = ExpectedException.none();

  private BatfishLogger _logger;

  @Before
  public void setup() {
    _logger = new BatfishLogger(BatfishLogger.LEVELSTR_OUTPUT, false);
  }

  @Test
  public void testComputeAnswerMetadata() throws IOException {
    String columnName = "col";
    int value = 5;

    Answer testAnswer = new Answer();
    testAnswer.addAnswerElement(
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value)));
    testAnswer.setStatus(AnswerStatus.SUCCESS);
    List<ColumnAggregation> aggregations =
        ImmutableList.of(new ColumnAggregation(Aggregation.MAX, columnName));

    assertThat(
        AnswerMetadataUtil.computeAnswerMetadata(testAnswer, aggregations, _logger),
        equalTo(
            new AnswerMetadata(
                new Metrics(
                    ImmutableList.of(
                        new ColumnAggregationResult(Aggregation.MAX, columnName, value)),
                    1),
                AnswerStatus.SUCCESS)));
  }

  @Test
  public void testComputeAnswerMetadataUnsuccessfulAnswer() throws IOException {
    String columnName = "col";

    Answer testAnswer = new Answer();
    testAnswer.setStatus(AnswerStatus.FAILURE);
    List<ColumnAggregation> aggregations =
        ImmutableList.of(new ColumnAggregation(Aggregation.MAX, columnName));

    assertThat(
        AnswerMetadataUtil.computeAnswerMetadata(testAnswer, aggregations, _logger),
        equalTo(new AnswerMetadata(null, AnswerStatus.FAILURE)));
  }

  @Test
  public void testComputeAnswerMetadataFailedComputation() throws IOException {
    String columnName = "col";
    int value = 5;

    Answer testAnswer = new Answer();
    testAnswer.addAnswerElement(
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value)));
    testAnswer.setStatus(AnswerStatus.SUCCESS);
    List<ColumnAggregation> aggregations =
        ImmutableList.of(new ColumnAggregation(Aggregation.MAX, "fakeColumn"));

    assertThat(
        AnswerMetadataUtil.computeAnswerMetadata(testAnswer, aggregations, _logger),
        equalTo(new AnswerMetadata(null, AnswerStatus.FAILURE)));
  }

  @Test
  public void testComputeColumnAggregations() {
    String columnName = "col";
    int value = 5;

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value));
    List<ColumnAggregation> aggregations =
        ImmutableList.of(new ColumnAggregation(Aggregation.MAX, columnName));

    assertThat(
        AnswerMetadataUtil.computeColumnAggregations(table, aggregations, _logger),
        equalTo(ImmutableList.of(new ColumnAggregationResult(Aggregation.MAX, columnName, value))));
  }

  @Test
  public void testComputeColumnAggregationMax() {
    String columnName = "col";
    int value = 5;

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value));
    ColumnAggregation columnAggregation = new ColumnAggregation(Aggregation.MAX, columnName);

    assertThat(
        AnswerMetadataUtil.computeColumnAggregation(table, columnAggregation, _logger),
        equalTo(new ColumnAggregationResult(Aggregation.MAX, columnName, value)));
  }

  @Test
  public void testComputeColumnMaxOneRowInteger() {
    String columnName = "col";
    int value = 5;

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value));

    assertThat(AnswerMetadataUtil.computeColumnMax(table, columnName, _logger), equalTo(value));
  }

  @Test
  public void testComputeColumnMaxOneRowIssue() {
    String columnName = "col";
    int severity = 5;
    Issue value = new Issue("blah", severity, new Issue.Type("1", "2"));

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.ISSUE, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value));

    assertThat(AnswerMetadataUtil.computeColumnMax(table, columnName, _logger), equalTo(severity));
  }

  @Test
  public void testComputeColumnMaxTwoRows() {
    String columnName = "col";
    int value1 = 5;
    int value2 = 10;

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value1))
            .addRow(Row.of(columnName, value2));

    assertThat(AnswerMetadataUtil.computeColumnMax(table, columnName, _logger), equalTo(value2));
  }

  @Test
  public void testComputeColumnMaxNoRows() {
    String columnName = "col";

    TableAnswerElement table =
        new TableAnswerElement(
            new TableMetadata(
                ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                new DisplayHints().getTextDesc()));

    assertThat(AnswerMetadataUtil.computeColumnMax(table, columnName, _logger), nullValue());
  }

  @Test
  public void testComputeColumnMaxInvalidColumn() {
    String columnName = "col";
    String invalidColumnName = "invalid";
    int value = 5;

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.INTEGER, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value));

    _thrown.expect(IllegalArgumentException.class);
    AnswerMetadataUtil.computeColumnMax(table, invalidColumnName, _logger);
  }

  @Test
  public void testComputeColumnMaxInvalidSchema() {
    String columnName = "col";
    String value = "hello";

    TableAnswerElement table =
        new TableAnswerElement(
                new TableMetadata(
                    ImmutableList.of(new ColumnMetadata(columnName, Schema.STRING, "foobar")),
                    new DisplayHints().getTextDesc()))
            .addRow(Row.of(columnName, value));

    _thrown.expect(UnsupportedOperationException.class);
    AnswerMetadataUtil.computeColumnMax(table, columnName, _logger);
  }
}
