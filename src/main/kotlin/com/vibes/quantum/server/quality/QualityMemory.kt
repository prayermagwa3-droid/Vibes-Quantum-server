package com.vibes.quantum.server.quality

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.UUID
import java.util.Locale
import kotlin.math.max

@Serializable data class EvalCase(val id:String, val category:String, val prompt:String, val reference:String, val candidateAnswer:String="", val critical:Boolean=false, val rubric:String="Exact/reference comparison")
@Serializable data class EvalRunRequest(
 val engineVersion:String,
 val datasetVersion:String,
 val providerIds:List<String> = emptyList(),
 val configurationId:String="default",
 val cases:List<EvalCase>,
 val sampleSize:Int=100,
 val baselineScore:Double?=null,
 /** Compatibility/reporting field only; promotion uses the score measured from sampled answers. */
 val candidateScore:Double,
 val criticalFailures:Int=0,
 val privacySecurityPassed:Boolean=true,
 val avgLatencyMs:Long=0,
 val providerFailures:Int=0,
 val estimatedCost:Double=0.0,
 val baselineDatasetVersion:String?=null
)
@Serializable data class EvalRun(
 val id:String,
 val createdAt:String,
 val engineVersion:String,
 val datasetVersion:String,
 val configurationId:String,
 val providerIds:List<String>,
 val evaluated:Int,
 val sampleRequested:Int,
 val candidateScore:Double,
 val baselineScore:Double?,
 val regressionRate:Double,
 val criticalFailures:Int,
 val privacySecurityPassed:Boolean,
 val avgLatencyMs:Long,
 val providerFailures:Int,
 val estimatedCost:Double,
 val promoted:Boolean,
 val gateReasons:List<String>,
 val reviewRequired:Int,
 val categoryScores:Map<String,Double> = emptyMap(),
 val dimensionScores:Map<String,Double> = emptyMap(),
 val criticalCaseFailures:Int = 0,
 val sampledCaseIds:List<String> = emptyList(),
 val missingAnswerCaseIds:List<String> = emptyList()
)
@Serializable data class ReviewItem(val id:String,val kind:String,val subject:String,val reason:String,val createdAt:String,val status:String="pending")
@Serializable private data class QualityGateState(val runs:List<EvalRun> = emptyList(), val reviews:List<ReviewItem> = emptyList())

/**
 * Deterministic benchmark gate. Scores are derived from candidate answers, never trusted from
 * request.candidateScore. Lexical alignment is a regression signal, not a factuality certificate.
 * When stateFile is supplied, history and review items survive server restarts using atomic writes.
 */
class QualityGateService(private val maxHistory:Int=500, private val stateFile:java.nio.file.Path?=null) {
 private val json = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
 private val runs= arrayListOf<EvalRun>(); private val reviews= arrayListOf<ReviewItem>()
 init {
  require(maxHistory in 1..10000)
  stateFile?.let { path ->
   if (java.nio.file.Files.exists(path)) {
    val state = try { json.decodeFromString<QualityGateState>(java.nio.file.Files.readString(path)) }
     catch (failure:Exception) { throw IllegalStateException("Quality-gate history could not be read; source file was preserved", failure) }
    require(state.runs.size <= maxHistory && state.reviews.size <= maxHistory*4) { "Quality-gate history exceeds configured limits" }
    runs.addAll(state.runs); reviews.addAll(state.reviews)
   }
  }
 }

 @Synchronized fun evaluate(r:EvalRunRequest):EvalRun {
  require(r.engineVersion.isNotBlank() && r.engineVersion.length <= 128 && r.datasetVersion.isNotBlank() && r.datasetVersion.length <= 128)
  require(r.configurationId.isNotBlank() && r.configurationId.length <= 128)
  require(r.cases.isNotEmpty() && r.cases.size <= 100000 && r.cases.map { it.id }.distinct().size == r.cases.size)
  require(r.cases.all { it.id.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && it.category.isNotBlank() && it.category.length <= 80 && it.prompt.isNotBlank() && it.prompt.length <= 20000 && it.reference.isNotBlank() && it.reference.length <= 20000 && it.candidateAnswer.length <= 20000 }) { "Every case needs a unique safe ID, category, prompt, and reference; answer lengths are bounded" }
  require(r.sampleSize in 1..10000)
  require(r.candidateScore.isFinite() && r.candidateScore in 0.0..1.0)
  require(r.baselineScore==null || (r.baselineScore.isFinite() && r.baselineScore in 0.0..1.0))
  require(r.criticalFailures in 0..r.cases.size && r.providerFailures >= 0 && r.avgLatencyMs >= 0)
  require(r.estimatedCost.isFinite() && r.estimatedCost >= 0.0)
  require(r.providerIds.size <= 100 && r.providerIds.all { it.isNotBlank() && it.length <= 128 })
  val sampled=stableSample(r.cases,r.datasetVersion,minOf(r.sampleSize,r.cases.size)); val n=sampled.size
  val reasons= mutableListOf<String>()
  if (r.baselineScore != null && r.baselineDatasetVersion == null) reasons += "Baseline dataset version is required when a baseline score is supplied"
  if (r.baselineDatasetVersion != null && r.baselineDatasetVersion != r.datasetVersion) reasons += "Baseline dataset version differs from candidate dataset version"
  val missing = sampled.filter { it.candidateAnswer.isBlank() }.map { it.id }
  if (missing.isNotEmpty()) reasons += "Some sampled cases have no candidate answer; omitted answers count as failures"
  val scored=sampled.map { it to score(it.reference,it.candidateAnswer) }
  val measured=if(scored.isEmpty()) 0.0 else scored.count { (case,_) -> normalize(case.reference)==normalize(case.candidateAnswer) }.toDouble()/scored.size
  val dimensionNames=listOf("exact_match","reference_recall","answer_precision","lexical_f1","non_empty")
  val dimensions=dimensionNames.associateWith { key -> if(scored.isEmpty()) 0.0 else scored.map { (_,metrics) -> metrics.getValue(key) }.average() }
  val categoryScores=sampled.groupBy { it.category }.toSortedMap().mapValues { (_,cases) ->
   if(cases.isEmpty()) 0.0 else cases.count { normalize(it.reference)==normalize(it.candidateAnswer) }.toDouble()/cases.size
  }
  val actualCriticalFailures=sampled.count { it.critical && normalize(it.reference)!=normalize(it.candidateAnswer) }
  val criticalCount=maxOf(r.criticalFailures,actualCriticalFailures)
  if(criticalCount>0) reasons += "Critical benchmark failures"
  if(!r.privacySecurityPassed) reasons += "Privacy/security gate failed"
  val baselineComparable = r.baselineScore != null && r.baselineDatasetVersion == r.datasetVersion
  if(baselineComparable && max(0.0,r.baselineScore!!-measured)>0.02) reasons += "Candidate score regressed by more than 2 percentage points"
  if(r.providerFailures>0) reasons += "Provider failures require review"
  val regression=if(baselineComparable) max(0.0,r.baselineScore!!-measured) else 0.0
  val reviewCount=r.cases.count { it.critical }
  val run=EvalRun(
   id="eval_${UUID.randomUUID().toString().replace("-","").take(16)}", createdAt=Instant.now().toString(),
   engineVersion=r.engineVersion,datasetVersion=r.datasetVersion,configurationId=r.configurationId,providerIds=r.providerIds.distinct(),
   evaluated=n,sampleRequested=r.sampleSize,candidateScore=measured,baselineScore=r.baselineScore,regressionRate=regression,
   criticalFailures=criticalCount,privacySecurityPassed=r.privacySecurityPassed,avgLatencyMs=r.avgLatencyMs,providerFailures=r.providerFailures,
   estimatedCost=r.estimatedCost,promoted=reasons.isEmpty(),gateReasons=reasons,reviewRequired=reviewCount,
   categoryScores=categoryScores,dimensionScores=dimensions,criticalCaseFailures=actualCriticalFailures,
   sampledCaseIds=sampled.map { it.id },missingAnswerCaseIds=missing
  )
  val oldRuns=runs.toList(); val oldReviews=reviews.toList()
  runs.add(run); while(runs.size>maxHistory) runs.removeAt(0)
  reasons.forEach { reviews.add(ReviewItem("review_${UUID.randomUUID().toString().take(12)}","quality_gate",run.id,it,Instant.now().toString())) }
  sampled.filter { it.critical && normalize(it.reference)!=normalize(it.candidateAnswer) }.forEach {
   reviews.add(ReviewItem("review_${UUID.randomUUID().toString().take(12)}","critical_case",it.id,"Critical case failed reference comparison",Instant.now().toString()))
  }
  while(reviews.size>maxHistory*4) reviews.removeAt(0)
  try { persist() } catch (failure:Exception) { runs.clear(); runs.addAll(oldRuns); reviews.clear(); reviews.addAll(oldReviews); throw IllegalStateException("Quality-gate history could not be persisted",failure) }
  return run
 }

 @Synchronized fun history()=runs.toList().reversed()
 @Synchronized fun reviewQueue()=reviews.filter { it.status=="pending" }.take(500)

 private fun stableSample(cases:List<EvalCase>, datasetVersion:String, size:Int):List<EvalCase> {
  val buckets=cases.groupBy { it.category }.toSortedMap().mapValues { (_,values) ->
   values.sortedBy { sha256("$datasetVersion:${it.id}") }.toMutableList()
  }
  val selected=mutableListOf<EvalCase>()
  while(selected.size<size) {
   var advanced=false
   for(category in buckets.keys) {
    val bucket=buckets.getValue(category)
    if(bucket.isNotEmpty() && selected.size<size) { selected += bucket.removeAt(0); advanced=true }
   }
   if(!advanced) break
  }
  return selected
 }
 private fun score(reference:String,answer:String):Map<String,Double> {
  val expected=terms(reference); val actual=terms(answer); val overlap=expected.intersect(actual).size.toDouble()
  val recall=if(expected.isEmpty()) 0.0 else overlap/expected.size
  val precision=if(actual.isEmpty()) 0.0 else overlap/actual.size
  val f1=if(precision+recall==0.0) 0.0 else 2*precision*recall/(precision+recall)
  return mapOf("exact_match" to if(normalize(reference)==normalize(answer)) 1.0 else 0.0,
   "reference_recall" to recall,"answer_precision" to precision,"lexical_f1" to f1,"non_empty" to if(answer.isBlank()) 0.0 else 1.0)
 }
 private fun terms(text:String):Set<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase(Locale.ROOT)).map { it.value }.filter { it !in STOP_WORDS }.toSet()
 private fun normalize(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ").trimEnd('.','!','?')
 private fun sha256(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
 private fun persist() {
  val path=stateFile ?: return
  path.parent?.let { java.nio.file.Files.createDirectories(it) }
  val temp=path.resolveSibling(path.fileName.toString()+".tmp")
  java.nio.file.Files.writeString(temp,json.encodeToString(QualityGateState(runs.toList(),reviews.toList())))
  try { java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
  catch(_:java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
  finally { java.nio.file.Files.deleteIfExists(temp) }
 }
 companion object { private val STOP_WORDS=setOf("the","and","for","with","from","that","this","you","your","are","was","were","what","when","where","how","why","does","can","will","have","has","had","but","not","into","than","then","their","there","they","them","its") }
}

@Serializable data class MemoryClaim(val id:String,val text:String,val domain:String,val source:String,val confidence:Double,val approved:Boolean=false,val lastVerified:String?=null,val expiresAt:String?=null,val status:String="active",val version:Int=1,val usedCount:Long=0)
@Serializable data class MemoryPolicy(val domain:String,val maxAgeDays:Int?=null,val reverifyAfterDays:Int?=null,val neverAutoDelete:Boolean=true)
@Serializable data class MaintenancePreview(val previewId:String,val createdAt:String,val kept:Int,val refreshDue:Int,val quarantined:Int,val duplicatesSuggested:Int,val softDeleteCandidates:Int,val destructiveConfirmationRequired:Boolean=true,val reasons:List<String>)
@Serializable data class MaintenanceRequest(val confirmPreviewId:String?=null,val confirmSoftDelete:Boolean=false,val storageQuotaRecords:Int=10000)
@Serializable data class MaintenanceReport(val previewId:String,val kept:Int,val refreshed:Int,val merged:Int,val quarantined:Int,val softDeleted:Int,val restored:Int,val reasons:List<String>,val completedAt:String)

/** Reversible lifecycle manager. Age alone never removes an approved claim. */
class MemoryLifecycleService {
 private val claims= linkedMapOf<String,MemoryClaim>(); private val history= mutableMapOf<String,MutableList<MemoryClaim>>(); private val previews= mutableMapOf<String,MaintenancePreview>(); private val deleted= mutableMapOf<String,MemoryClaim>()
 private val policies=mapOf("weather" to MemoryPolicy("weather",1,1),"market_price" to MemoryPolicy("market_price",1,1),"news" to MemoryPolicy("news",7,3),"software_version" to MemoryPolicy("software_version",90,30),"mathematics" to MemoryPolicy("mathematics",null,3650),"general" to MemoryPolicy("general",null,180))
 @Synchronized fun upsert(c:MemoryClaim):MemoryClaim { require(c.id.isNotBlank() && c.text.isNotBlank() && c.confidence in 0.0..1.0); history.getOrPut(c.id){ mutableListOf() }.add(claims[c.id] ?: c); claims[c.id]=c; return c }
 @Synchronized fun list()=claims.values.toList()
 @Synchronized fun policyDirectory()=policies.values.toList()
 @Synchronized fun preview(quota:Int=10000):MaintenancePreview {
  require(quota in 1..1000000)
  val refresh=claims.values.count { c -> val p=policies[c.domain] ?: policies.getValue("general"); c.status=="active" && p.reverifyAfterDays!=null && c.lastVerified==null }
  val quarantine=claims.values.count { it.status=="active" && (it.confidence<0.35 || it.status=="contradicted") }
  val dupes=claims.values.groupBy { it.text.trim().lowercase() }.values.count { it.size>1 }
  val excess=max(0,claims.size-quota)
  val p=MaintenancePreview("memprev_${UUID.randomUUID().toString().take(12)}",Instant.now().toString(),claims.size-refresh-quarantine,refresh,quarantine,dupes,excess,true,listOf("Expiry is domain-specific","Approved claims are never removed for age or inactivity alone","Duplicates are suggestions until reviewed","Soft deletion is reversible"))
  previews[p.previewId]=p; return p
 }
 @Synchronized fun maintain(req:MaintenanceRequest):MaintenanceReport {
  val p=previews[req.confirmPreviewId] ?: throw IllegalArgumentException("Create a current preview before maintenance")
  var quarantined=0; var soft=0
  claims.values.toList().forEach { c ->
   if(c.status=="active" && c.confidence<0.2) { history.getOrPut(c.id){ mutableListOf() }.add(c); claims[c.id]=c.copy(status="quarantined",version=c.version+1); quarantined++ }
  }
  if(req.confirmSoftDelete) {
   require(req.confirmPreviewId==p.previewId) { "Explicit matching preview confirmation required" }
   val excess=max(0,claims.size-req.storageQuotaRecords)
   claims.values.filter { !it.approved && it.status=="quarantined" }.take(excess).forEach { c -> deleted[c.id]=c; history.getOrPut(c.id){ mutableListOf() }.add(c); claims.remove(c.id); soft++ }
  }
  return MaintenanceReport(p.previewId,claims.size, p.refreshDue,0,quarantined,soft,0,listOf("Approved facts preserved","No age-only deletion","All removals are soft-deleted and restorable during retention"),Instant.now().toString())
 }
 @Synchronized fun restore(id:String):MemoryClaim? { val c=deleted.remove(id) ?: return null; claims[id]=c.copy(status="active",version=c.version+1); return claims.getValue(id) }
 @Synchronized fun versions(id:String)=history[id]?.toList() ?: emptyList()
}
