package com.vibes.quantum.server.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.nio.file.Files

class QualityMemoryTest {
 @Test fun measuredScoreUsesReferenceCasesAndBlocksRegression() {
  val q=QualityGateService()
  val run=q.evaluate(EvalRunRequest("2.7","bench-1",cases=listOf(
   EvalCase("1","facts","q","The answer.",candidateAnswer="The answer"),
   EvalCase("2","facts","q2","Correct",candidateAnswer="wrong")
  ),sampleSize=2,baselineScore=1.0,candidateScore=1.0))
  assertEquals(0.5,run.candidateScore)
  assertFalse(run.promoted)
  assertTrue(run.gateReasons.isNotEmpty())
 }
 @Test fun privacyFailureBlocksPromotionAndQueuesReview() {
  val q=QualityGateService(); val r=q.evaluate(EvalRunRequest("2.7","bench-1",cases=listOf(EvalCase("1","safety","q","ok",candidateAnswer="ok")),privacySecurityPassed=false))
  assertFalse(r.promoted); assertTrue(q.reviewQueue().isNotEmpty())
 }
 @Test fun omittedCandidateAnswersCountAsFailuresAndCriticalCasesEnterReviewQueue() {
  val q=QualityGateService()
  val run=q.evaluate(EvalRunRequest("2.7","bench-v2",cases=listOf(
   EvalCase("critical-1","safety","safe?","Ask a trusted adult",candidateAnswer="",critical=true),
   EvalCase("fact-1","facts","2+2?","Four",candidateAnswer="Four")
  ),sampleSize=2,candidateScore=1.0))
  assertEquals(0.5,run.candidateScore)
  assertEquals(1,run.criticalCaseFailures)
  assertEquals(listOf("critical-1"),run.missingAnswerCaseIds)
  assertFalse(run.promoted)
  assertTrue(q.reviewQueue().any { it.kind=="critical_case" })
 }

 @Test fun benchmarkSamplingIsStableAndCategoryScoresAreReported() {
  val cases=listOf(
   EvalCase("a","facts","q","A",candidateAnswer="A"),
   EvalCase("b","facts","q","B",candidateAnswer="wrong"),
   EvalCase("c","code","q","C",candidateAnswer="C"),
   EvalCase("d","safety","q","D",candidateAnswer="D")
  )
  val q=QualityGateService()
  val first=q.evaluate(EvalRunRequest("2.7","bench-v3",cases=cases,sampleSize=3,candidateScore=0.0))
  val second=q.evaluate(EvalRunRequest("2.7","bench-v3",cases=cases.reversed(),sampleSize=3,candidateScore=0.0))
  assertEquals(first.sampledCaseIds,second.sampledCaseIds)
  assertTrue(first.categoryScores.isNotEmpty())
  assertTrue(first.dimensionScores.containsKey("lexical_f1"))
  assertEquals(first.candidateScore,first.dimensionScores["exact_match"])
 }

 @Test fun baselineWithDifferentDatasetVersionCannotPromote() {
  val q=QualityGateService()
  val result=q.evaluate(EvalRunRequest("2.7","current",cases=listOf(EvalCase("a","facts","q","ok",candidateAnswer="ok")),sampleSize=1,candidateScore=1.0,baselineScore=1.0,baselineDatasetVersion="old"))
  assertFalse(result.promoted)
  assertTrue(result.gateReasons.any { it.contains("dataset version") })
 }

 @Test fun qualityHistoryPersistsAcrossServiceInstances() {
  val file=Files.createTempDirectory("vq-quality-test").resolve("history.json")
  val first=QualityGateService(stateFile=file)
  first.evaluate(EvalRunRequest("2.7","bench-persist",cases=listOf(EvalCase("a","facts","q","ok",candidateAnswer="ok")),sampleSize=1,candidateScore=0.0))
  val reopened=QualityGateService(stateFile=file)
  assertEquals(1,reopened.history().size)
  assertEquals("bench-persist",reopened.history().single().datasetVersion)
 }

 @Test fun invalidMetricInputsAreRejected() {
  val q=QualityGateService()
  assertFailsWith<IllegalArgumentException> {
   q.evaluate(EvalRunRequest("2.7","bench",cases=listOf(EvalCase("a","facts","q","ok",candidateAnswer="ok")),sampleSize=1,candidateScore=1.0,estimatedCost=Double.NaN))
  }
 }

 @Test fun approvedOldClaimsAreNotDeletedByPreviewOrMaintenance() {
  val m=MemoryLifecycleService(); m.upsert(MemoryClaim("claim1","A stable mathematical fact","mathematics","textbook",1.0,approved=true))
  val p=m.preview(); val report=m.maintain(MaintenanceRequest(confirmPreviewId=p.previewId,confirmSoftDelete=true))
  assertEquals(1,report.kept); assertEquals("active",m.list().single().status)
 }
 @Test fun lowConfidenceClaimsAreQuarantinedAndVersioned() {
  val m=MemoryLifecycleService(); m.upsert(MemoryClaim("claim2","uncertain claim","general","unknown",0.1))
  val p=m.preview(); val report=m.maintain(MaintenanceRequest(confirmPreviewId=p.previewId))
  assertEquals(1,report.quarantined); assertEquals("quarantined",m.list().single().status); assertTrue(m.versions("claim2").isNotEmpty())
 }
}
