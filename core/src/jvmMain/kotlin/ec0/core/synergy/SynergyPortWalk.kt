/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Per-profile port walk — models upstream RetryAndChangePortInterceptor
 * (base/socket/RetryAndChangePortInterceptor.java). Call sites pass
 * (list[0], list, list.size()) — WSCM:267 (computer) / :481 (pad) — so
 * RetryCount = list.size() and the walk is: firstPort, then SERVERS[i] for
 * i in 0 until list.size(), with FirstPort reset to SERVERS[i] each round
 * (interceptor line: this.FirstPort = this.SERVERS.get(i)).
 *
 * The 1-arg/2-arg switchServer decompile is ambiguous (JADX artifact); the
 * 2-arg reading ("replace current URL port with SERVERS[i]") reproduces the
 * catalog's reviewer-verified effective order for both profiles — the 1-arg
 * reading never reaches the last port within RetryCount. Documented in
 * docs/re/synergy-transport.md §Port-walk semantics.
 */
package ec0.core.synergy

class SynergyPortWalk(
    val firstPort: Int,
    val servers: List<Int>,
) {
    /** Upstream passes list.size() as RetryCount at both call sites. */
    val retryCount: Int = servers.size

    /**
     * Faithful attempt sequence: the initial attempt on firstPort, then
     * SERVERS[i] per retry round. Consecutive duplicates are real (upstream
     * attempts SERVERS[0] even when it equals firstPort). The interceptor's
     * `FirstPort = SERVERS[i]` reset has no observable effect under the
     * 2-arg switchServer reading; it matters only for the 1-arg alternative
     * (see docs/re/synergy-transport.md §Port-walk semantics).
     */
    fun attempts(): List<Int> = buildList {
        add(firstPort)
        addAll(servers)
    }

    /** Effective distinct ports in first-attempted order (catalog cross-check view). */
    fun effectiveOrder(): List<Int> = attempts().distinct()

    companion object {
        fun computer(): SynergyPortWalk = SynergyPortWalk(
            firstPort = SynergyTransport.COMPUTER_PORT_LIST.first(),
            servers = SynergyTransport.COMPUTER_PORT_LIST,
        )

        fun pad(): SynergyPortWalk = SynergyPortWalk(
            firstPort = SynergyTransport.PAD_PORT_LIST.first(),
            servers = SynergyTransport.PAD_PORT_LIST,
        )
    }
}
