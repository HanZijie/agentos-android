// Stand-in for IAcpService in the S2 spike: only measures bind latency and starts fake tasks.
package org.agentos.spike.s2;

interface IProbe {
    /** Returns the server's SystemClock.elapsedRealtime(); the first call after bind is "first response". */
    long ping(long clientElapsedRealtime);

    /**
     * Adds a fake task that keeps the runtime busy for durationMs (<= 0 means until stopped),
     * holding ballastMb of memory. Returns a status line: "tasks=<n> fg=<0|1> fgError=<...>".
     */
    String startTask(long durationMs, int ballastMb);

    /** Current heartbeat text. */
    String status();
}
