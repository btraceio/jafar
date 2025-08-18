// JFR Shell Example: Thread Analysis
// This script analyzes thread activity in a JFR recording

println "=== Thread Analysis Example ==="

// Open recording (replace with your file path)
// open("path/to/your/recording.jfr")

// Data structures for analysis
@Field def threadSamples = [:]
@Field def threadStates = [:]
@Field def methodHotspots = [:]

// Analyze execution samples
handle(JFRExecutionSample) { event, ctl ->
    def threadId = event.sampledThread().javaThreadId()
    def threadName = event.sampledThread().name()
    def state = event.state()
    
    // Count samples per thread
    def key = "${threadId}:${threadName}"
    threadSamples[key] = (threadSamples[key] ?: 0) + 1
    
    // Track thread states
    threadStates[state] = (threadStates[state] ?: 0) + 1
    
    // Collect method hotspots
    def frames = event.stackTrace().frames()
    if (frames.length > 0) {
        def method = frames[0].method().name()
        methodHotspots[method] = (methodHotspots[method] ?: 0) + 1
    }
}

// Run the analysis
run()

// Display results
println "\n=== Top 10 Most Active Threads ==="
threadSamples.sort { -it.value }.take(10).eachWithIndex { entry, i ->
    def (threadId, threadName) = entry.key.split(':', 2)
    println "${i+1:2d}. Thread ${threadId} (${threadName}): ${entry.value} samples"
}

println "\n=== Thread State Distribution ==="
def totalStates = threadStates.values().sum()
threadStates.sort { -it.value }.each { state, count ->
    def percentage = (count * 100.0 / totalStates).round(1)
    println "  ${state}: ${count} (${percentage}%)"
}

println "\n=== Top 10 Method Hotspots ==="
methodHotspots.sort { -it.value }.take(10).eachWithIndex { entry, i ->
    println "${i+1:2d}. ${entry.key}: ${entry.value} samples"
}

// Export results for further analysis
def results = [
    threadSamples: threadSamples,
    threadStates: threadStates,
    methodHotspots: methodHotspots,
    summary: [
        totalThreads: threadSamples.size(),
        totalSamples: threadSamples.values().sum(),
        uniqueMethods: methodHotspots.size()
    ]
]

export(results, "thread_analysis_results.json")
println "\n=== Results exported to thread_analysis_results.json ==="