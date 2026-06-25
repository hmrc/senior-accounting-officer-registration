import uk.gov.hmrc.DefaultBuildSettings

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "3.3.6"

lazy val microservice = Project("senior-accounting-officer-registration", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin, SwaggerPlugin)
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    // https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
    // suppress warnings in generated routes files
    scalacOptions += "-Wconf:src=routes/.*:s",
    PlayKeys.playDefaultPort := 10059
  )
  .settings(CodeCoverageSettings.settings *)
  .settings(scalafixSettings *)
  .settings(playSwaggerSettings *)

val scalafixSettings: Seq[Setting[?]] = Seq(
  semanticdbEnabled := true
)

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test")
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.it)

val playSwaggerSettings: Seq[Setting[?]] = Seq(
  swaggerDomainNameSpaces := Seq(
    "uk.gov.hmrc.senioraccountingofficerregistration.models"
  ),
  swaggerRoutesFile := "app.routes",
  swaggerV3         := true,
  swaggerPrettyJson := true
)

addCommandAlias("checkLint", "scalafmtSbtCheck;scalafmtCheckAll")
addCommandAlias("lint", "scalafixAll;scalafmtSbt;scalafmtAll")
