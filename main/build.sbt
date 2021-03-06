name := "processors-main"

libraryDependencies ++= {
  val akkaV = "2.5.4"
  val json4sVersion = "3.5.2"

  Seq(
    // common tools
    "org.scala-lang.modules"  %%  "scala-parser-combinators"  % "1.0.4",
    "com.typesafe"             %  "config"                    % "1.3.1",
    "com.io7m.xom"             %  "xom"                       % "1.2.10",
    "org.json4s"              %%  "json4s-core"               % json4sVersion,
    "org.json4s"              %%  "json4s-jackson"            % json4sVersion,
    "jline"                    %  "jline"                     % "2.12.1",
    "commons-io"               %  "commons-io"                % "2.5",
    "ai.lum"                  %%  "common"                    % "0.0.8",

    // for machine learning
    "de.bwaldvogel"            %  "liblinear"                 % "2.30",
    "tw.edu.ntu.csie"          %  "libsvm"                    % "3.23",
    "org.clulab"              %%  "fatdynet"                  % "0.2.5", // "0-cuda.2.5-SNAPSHOT",

    // NLP tools used by CluProcessor
    "org.antlr"                %  "antlr4-runtime"            % "4.6",   // for tokenization
    "org.clulab"               %  "lemport"                   % "0.9.10", // Portuguese lemmatizer

    // logging
    "com.typesafe.scala-logging"  %%  "scala-logging"    % "3.7.2",
    "ch.qos.logback"               %  "logback-classic"  % "1.0.10",
    "org.slf4j"                    %  "slf4j-api"        % "1.7.10",

    // testing
    "org.scalatest"       %%  "scalatest"      % "3.0.1"  % "test",
    "com.typesafe.akka"   %%  "akka-testkit"   % akkaV    % "test",

    // trained models for the ML components used in both main and corenlp
    "org.clulab" %% "processors-models" % "0.0.2"
  )

}
