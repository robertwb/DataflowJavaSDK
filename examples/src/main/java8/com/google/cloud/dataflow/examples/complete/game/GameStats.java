/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.examples.complete.game;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.dataflow.examples.common.DataflowExampleUtils;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.PipelineResult;
import com.google.cloud.dataflow.sdk.coders.AvroCoder;
import com.google.cloud.dataflow.sdk.coders.DefaultCoder;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.BigQueryIO.Write.CreateDisposition;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.options.Default;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.options.Validation;
import com.google.cloud.dataflow.sdk.runners.DataflowPipelineRunner;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.DoFn.RequiresWindowAccess;
import com.google.cloud.dataflow.sdk.transforms.MapElements;
import com.google.cloud.dataflow.sdk.transforms.Mean;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.SerializableComparator;
import com.google.cloud.dataflow.sdk.transforms.SimpleFunction;
import com.google.cloud.dataflow.sdk.transforms.Values;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.transforms.windowing.AfterEach;
import com.google.cloud.dataflow.sdk.transforms.windowing.AfterProcessingTime;
import com.google.cloud.dataflow.sdk.transforms.windowing.AfterWatermark;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.IntervalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.OutputTimeFns;
import com.google.cloud.dataflow.sdk.transforms.windowing.Repeatedly;
import com.google.cloud.dataflow.sdk.transforms.windowing.Sessions;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PDone;
import com.google.cloud.dataflow.sdk.values.TypeDescriptor;

import org.apache.avro.reflect.Nullable;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * This class is the fourth in a series of four pipelines that tell a story in a 'gaming'
 * domain, following {@link UserScore}, {@link HourlyTeamScore}, and {@link LeaderBoard}.
 * New concepts: session windows and finding session duration; use of both
 * singleton and non-singleton side inputs.
 *
 * <p> This pipeline builds on the {@link LeaderBoard} functionality, and adds some "business
 * intelligence" analysis: abuse detection and usage patterns. The pipeline derives the Mean user
 * score sum for a window, and uses that information to identify likely spammers/robots. (The robots
 * have a higher click rate than the human users). The 'robot' users are then filtered out when
 * calculating the team scores.
 *
 * <p> Additionally, user sessions are tracked: that is, we find bursts of user activity using
 * session windows. Then, the mean session duration information is recorded in the context of
 * subsequent fixed windowing. (This could be used to tell us what games are giving us greater
 * user retention).
 *
 * <p> Run {@link injector.Injector} to generate pubsub data for this pipeline.  The Injector
 * documentation provides more detail.
 *
 * <p> To execute this pipeline using the Dataflow service, specify the pipeline configuration
 * like this:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --stagingLocation=gs://YOUR_STAGING_DIRECTORY
 *   --runner=BlockingDataflowPipelineRunner
 *   --dataset=YOUR-DATASET
 *   --topic=projects/YOUR-PROJECT/topics/YOUR-TOPIC
 * }
 * </pre>
 * where the BigQuery dataset you specify must already exist. The PubSub topic you specify should
 * be the same topic to which the Injector is publishing.
 */
public class GameStats extends LeaderBoard {

  private static final String TIMESTAMP_ATTRIBUTE = "timestamp_ms";
  private static final Logger LOG = LoggerFactory.getLogger(GameStats.class);

  private static DateTimeFormatter fmt =
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")
          .withZone(DateTimeZone.forTimeZone(TimeZone.getTimeZone("PST")));
  static final Duration FIVE_MINUTES = Duration.standardMinutes(5);
  static final Duration TEN_MINUTES = Duration.standardMinutes(10);


  /**
   * Filter out all but those users with a high clickrate, which we will consider as 'spammy' uesrs.
   * We do this by finding the mean total score per user, then using that information as a side
   * input to filter out all but those user scores that are > (mean * SCORE_WEIGHT)
   */
  // [START DocInclude_AbuseDetect]
  public static class CalculateSpammyUsers
      extends PTransform<PCollection<KV<String, Integer>>, PCollection<KV<String, Integer>>> {
    private static final Logger LOG = LoggerFactory.getLogger(CalculateSpammyUsers.class);
    private static final double SCORE_WEIGHT = 2.5;

    @Override
    public PCollection<KV<String, Integer>> apply(PCollection<KV<String, Integer>> userScores) {

      // Get the sum of scores for each user.
      PCollection<KV<String, Integer>> sumScores = userScores
          .apply("UserSum", Sum.<String>integersPerKey());

      // Extract the score from each element, and use it to find the global mean.
      final PCollectionView<Double> globalMeanScore = sumScores.apply(Values.<Integer>create())
          .apply(Mean.<Integer>globally().asSingletonView());

      // Filter the user sums using the global mean.
      PCollection<KV<String, Integer>> filtered = sumScores
          .apply(ParDo
              .named("ProcessAndFilter")
              // use the derived mean total score as a side input
              .withSideInputs(globalMeanScore)
              .of(new DoFn<KV<String, Integer>, KV<String, Integer>>() {
                private final Aggregator<Long, Long> numSpammerUsers =
                  createAggregator("SpammerUsers", new Sum.SumLongFn());
                @Override
                public void processElement(ProcessContext c) {
                  Integer score = c.element().getValue();
                  Double gmc = c.sideInput(globalMeanScore);
                  if (score > (gmc * SCORE_WEIGHT)) {
                    LOG.info("user " + c.element().getKey() + " spammer score " + score
                        + " with mean " + gmc);
                    numSpammerUsers.addValue(1L);
                    c.output(c.element());
                  }
                }
              }));
      return filtered;
    }
  }
  // [END DocInclude_AbuseDetect]

  /**
   * Calculate and output an element's session duration.
   */
  private static class UserSessionInfoFn extends DoFn<KV<String, Integer>, Integer>
      implements RequiresWindowAccess {

    @Override
    public void processElement(ProcessContext c) {
      IntervalWindow w = (IntervalWindow) c.window();
      int duration = new Duration(
          w.start(), w.end()).toPeriod().toStandardMinutes().getMinutes();
      c.output(duration);
    }
  }


  /**
   * Options supported by {@link GameStats}.
   */
  static interface Options extends LeaderBoard.Options {
    @Description("Pub/Sub topic to read from")
    @Validation.Required
    String getTopic();
    void setTopic(String value);

    @Description("Numeric value of fixed window duration for user analysis, in minutes")
    @Default.Integer(60)
    Integer getFixedWindowDuration();
    void setFixedWindowDuration(Integer value);

    @Description("Numeric value of gap between user sessions, in minutes")
    @Default.Integer(5)
    Integer getSessionGap();
    void setSessionGap(Integer value);

    @Description("Numeric value of fixed window for finding mean of user session duration, " +
        "in minutes")
    @Default.Integer(30)
    Integer getUserActivityWindowDuration();
    void setUserActivityWindowDuration(Integer value);

    @Description("Prefix used for the BigQuery table names")
    @Default.String("game_stats")
    String getTableName();
    void setTableName(String value);
  }

  /**
   * Format user activity information (objects of type UserActivityInfo) and write to BigQuery.
   * The constructor argument indicates the table prefix to use.
   */
  public static class WriteAverageSessionLengthToBigQuery
      extends PTransform<PCollection<Double>, PDone> {

    private final String tablePrefix;

    public WriteAverageSessionLengthToBigQuery(String tablePrefix) {
      this.tablePrefix = tablePrefix;
    }

    /**
     *  Convert the user activity info into a BigQuery TableRow.
     */
    private class BuildSessionActivityRowFn extends DoFn<Double, TableRow>
        implements RequiresWindowAccess {

      @Override
      public void processElement(ProcessContext c) {

        IntervalWindow w = (IntervalWindow) c.window();

        TableRow row = new TableRow()
         .set("window_start", fmt.print(w.start()))
         .set("mean_duration", c.element());
        c.output(row);
      }
    }

    /** Build the output table schema. */
    private TableSchema getMeanSchema() {
      List<TableFieldSchema> fields = new ArrayList<>();
      fields.add(new TableFieldSchema().setName("window_start").setType("STRING"));
      fields.add(new TableFieldSchema().setName("mean_duration").setType("FLOAT"));
      return new TableSchema().setFields(fields);
    }

    @Override
    public PDone apply(PCollection<Double> userInfo) {
      return userInfo
        .apply(ParDo.named("ConvertToUserInfoRow").of(new BuildSessionActivityRowFn()))
        .apply(BigQueryIO.Write
                  .to(getTable(userInfo.getPipeline(),
                      tablePrefix + "_mean_sessions"))
                  .withSchema(getMeanSchema())
                  .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED));
    }
  }


  public static void main(String[] args) throws Exception {

    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    // Enforce that this pipeline is always run in streaming mode.
    options.setStreaming(true);
    // Allow the pipeline to be cancelled automatically.
    options.setRunner(DataflowPipelineRunner.class);
    DataflowExampleUtils dataflowUtils = new DataflowExampleUtils(options);
    Pipeline pipeline = Pipeline.create(options);

    // Read Events from Pub/Sub using custom timestamps
    PCollection<GameActionInfo> rawEvents = pipeline
        .apply(PubsubIO.Read.timestampLabel(TIMESTAMP_ATTRIBUTE).topic(options.getTopic()))
        .apply(ParDo.named("ParseGameEvent").of(new ParseEventFn()));

    // Extract username/score pairs from the event stream
    PCollection<KV<String, Integer>> userEvents =
        rawEvents.apply("ExtractUserScore",
          MapElements.via((GameActionInfo gInfo) -> KV.of(gInfo.getUser(), gInfo.getScore()))
            .withOutputType(new TypeDescriptor<KV<String, Integer>>() {}));

    // Calculate the total score per user over fixed windows, and
    // cumulative updates for late data.
    final PCollectionView<Map<String, Integer>> spammersView = userEvents
      .apply(Window.named("FixedWindowsUser")
          .<KV<String, Integer>>into(FixedWindows.of(
              Duration.standardMinutes(options.getFixedWindowDuration())))
          )

      // Filter out everyone but those with (SCORE_WEIGHT * avg) clickrate.
      // These might be robots/spammers.
      .apply("CalculateSpammyUsers", new CalculateSpammyUsers())
      // Derive a view from the collection of spammer users. It will be used as a side input
      // in calculating the team score sums, below.
      .apply("CreateSpammersView", View.<String, Integer>asMap());

    // [START DocInclude_FilterAndCalc]
    // Calculate the total score per team over fixed windows,
    // and emit cumulative updates for late data. Uses the side input derived above-- the set of
    // suspected robots-- to filter out scores from those users from the sum.
    // Write the results to BigQuery.
    rawEvents
      .apply(Window.named("WindowIntoFixedWindows")
          .<GameActionInfo>into(FixedWindows.of(
              Duration.standardMinutes(options.getFixedWindowDuration())))
          )
      // Filter out the detected spammer users, using the side input derived above.
      .apply(ParDo.named("FilterOutSpammers")
              .withSideInputs(spammersView)
              .of(new DoFn<GameActionInfo, GameActionInfo>() {
                @Override
                public void processElement(ProcessContext c) {
                  // If the user is not in the spammers Map, output the data element.
                  if (c.sideInput(spammersView).get(c.element().getUser().trim()) == null) {
                    c.output(c.element());
                  }}}))
      // Extract and sum teamname/score pairs from the event data.
      .apply("ExtractTeamScore", new ExtractAndSumScore("team"))
      // [END DocInclude_FilterAndCalc]
      // Write the result to BigQuery
      .apply("WriteTeamSums",
             new WriteScoresToBigQuery(options.getTableName(), "team", true, false));

    // [START DocInclude_SessionCalc]
    // Calculate the total score for the users per session-- that is, a burst of activity
    // separated by a gap from further activity. Find and record the mean session lengths.
    // This information could help the game designers track the changing user engagement
    // as their set of games changes.
    userEvents
      .apply(Window.named("WindowIntoSessions")
            .<KV<String, Integer>>into(
                  Sessions.withGapDuration(Duration.standardMinutes(options.getSessionGap())))
        .withOutputTimeFn(OutputTimeFns.outputAtEndOfWindow())
        .withAllowedLateness(Duration.ZERO)
        .discardingFiredPanes())
      .apply("UserSessionSum", Sum.<String>integersPerKey())
      // Get the duration per session.
      .apply("UserSessionActivity", ParDo.of(new UserSessionInfoFn()))
      // [END DocInclude_SessionCalc]
      // [START DocInclude_Rewindow]
      // Re-window to process groups of session sums according to when the sessions complete.
      .apply(Window.named("WindowToExtractSessionMean")
            .<Integer>into(
                FixedWindows.of(Duration.standardMinutes(options.getUserActivityWindowDuration())))
            .withAllowedLateness(Duration.standardMinutes(options.getAllowedLateness()))
            .accumulatingFiredPanes())
      // Find the mean session duration in each window.
      .apply(Mean.<Integer>globally().withoutDefaults())
      // Write this info to a BigQuery table.
      .apply("WriteAvgSessionLength",
             new WriteAverageSessionLengthToBigQuery(options.getTableName()));
    // [END DocInclude_Rewindow]


    // Run the pipeline and wait for the pipeline to finish; capture cancellation requests from the
    // command line.
    PipelineResult result = pipeline.run();
    dataflowUtils.waitToFinish(result);
  }
}
