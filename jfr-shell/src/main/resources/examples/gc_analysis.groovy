// JFR Shell Example: Garbage Collection Analysis  
// This script analyzes GC activity and performance metrics

println "=== GC Analysis Example ==="

// Open recording (replace with your file path)
// open("path/to/your/recording.jfr")

// Data structures for GC analysis
@Field def gcPhases = [:]
@Field def gcCollections = []
@Field def heapSummaries = []

// Analyze GC phases
handle(JFRGCPhaseParallel) { event, ctl ->
    def phaseName = event.name()
    def duration = event.duration()
    
    if (!gcPhases[phaseName]) {
        gcPhases[phaseName] = [count: 0, totalDuration: 0L, maxDuration: 0L]
    }
    
    gcPhases[phaseName].count++
    gcPhases[phaseName].totalDuration += duration
    gcPhases[phaseName].maxDuration = Math.max(gcPhases[phaseName].maxDuration, duration)
}

// Analyze full GC collections
handle(JFRGarbageCollection) { event, ctl ->
    gcCollections << [
        name: event.name(),
        cause: event.cause(),
        duration: event.duration(),
        startTime: event.startTime()
    ]
}

// Collect heap summaries
handle(JFRGCHeapSummary) { event, ctl ->
    heapSummaries << [
        when: event.when(),
        heapUsed: event.heapUsed(),
        heapCommitted: event.committed(),
        startTime: event.startTime()
    ]
}

// Run the analysis
run()

// Display GC phase analysis
println "\n=== GC Phase Analysis ==="
gcPhases.sort { -it.value.totalDuration }.each { phase, stats ->
    def avgDuration = stats.totalDuration / stats.count
    println "Phase: ${phase}"
    println "  Count: ${stats.count}"
    println "  Total Duration: ${stats.totalDuration / 1_000_000}ms"
    println "  Avg Duration: ${avgDuration / 1_000_000}ms" 
    println "  Max Duration: ${stats.maxDuration / 1_000_000}ms"
    println ""
}

// Display GC collection summary
if (gcCollections) {
    println "\n=== GC Collections Summary ==="
    println "Total Collections: ${gcCollections.size()}"
    
    def totalGcTime = gcCollections.sum { it.duration }
    def avgGcTime = totalGcTime / gcCollections.size()
    def maxGcTime = gcCollections.max { it.duration }?.duration ?: 0
    
    println "Total GC Time: ${totalGcTime / 1_000_000}ms"
    println "Average GC Time: ${avgGcTime / 1_000_000}ms"
    println "Max GC Time: ${maxGcTime / 1_000_000}ms"
    
    // Group by cause
    def byCause = gcCollections.groupBy { it.cause }
    println "\nCollections by Cause:"
    byCause.each { cause, collections ->
        println "  ${cause}: ${collections.size()} collections"
    }
}

// Analyze heap usage trends
if (heapSummaries.size() > 1) {
    println "\n=== Heap Usage Analysis ==="
    
    def beforeGc = heapSummaries.findAll { it.when == "Before GC" }
    def afterGc = heapSummaries.findAll { it.when == "After GC" }
    
    if (beforeGc && afterGc) {
        def avgBeforeGc = beforeGc.collect { it.heapUsed }.sum() / beforeGc.size()
        def avgAfterGc = afterGc.collect { it.heapUsed }.sum() / afterGc.size()
        def avgFreed = avgBeforeGc - avgAfterGc
        def freePercentage = (avgFreed * 100.0 / avgBeforeGc).round(1)
        
        println "Average Heap Before GC: ${(avgBeforeGc / 1024 / 1024).round(1)}MB"
        println "Average Heap After GC: ${(avgAfterGc / 1024 / 1024).round(1)}MB"
        println "Average Freed per GC: ${(avgFreed / 1024 / 1024).round(1)}MB (${freePercentage}%)"
    }
    
    def maxHeapUsed = heapSummaries.max { it.heapUsed }?.heapUsed ?: 0
    def maxHeapCommitted = heapSummaries.max { it.heapCommitted }?.heapCommitted ?: 0
    
    println "Peak Heap Used: ${(maxHeapUsed / 1024 / 1024).round(1)}MB"
    println "Peak Heap Committed: ${(maxHeapCommitted / 1024 / 1024).round(1)}MB"
}

// Calculate GC efficiency metrics
if (gcCollections && heapSummaries) {
    println "\n=== GC Efficiency Metrics ==="
    
    def gcFrequency = gcCollections.size()
    def totalDuration = gcCollections.sum { it.duration }
    def gcOverhead = (totalDuration * 100.0 / session.uptime).round(3)
    
    println "GC Frequency: ${gcFrequency} collections"
    println "GC Overhead: ${gcOverhead}% of total time"
    
    if (gcOverhead > 5.0) {
        println "⚠️  HIGH GC OVERHEAD DETECTED! Consider heap tuning."
    } else if (gcOverhead > 2.0) {
        println "⚠️  Moderate GC overhead. Monitor heap usage."
    } else {
        println "✅ GC overhead is within acceptable range."
    }
}

// Export results
def results = [
    gcPhases: gcPhases,
    gcCollections: gcCollections,
    heapSummaries: heapSummaries,
    summary: [
        totalCollections: gcCollections.size(),
        totalPhases: gcPhases.size(),
        totalGcTime: gcCollections.sum { it.duration ?: 0 }
    ]
]

export(results, "gc_analysis_results.json")
println "\n=== Results exported to gc_analysis_results.json ==="