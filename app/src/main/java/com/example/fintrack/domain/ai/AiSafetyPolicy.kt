package com.example.fintrack.domain.ai

/**
 * Stage 10 / P22 — central safety & refusal policy (module 85).
 *
 * Every AI assistant output passes through this policy BEFORE it reaches the
 * UI. The policy encodes the App Bible non-goals as executable rules:
 *
 *  - no money movement / transfer execution
 *  - no bank login or credential handling
 *  - no investment advice or portfolio claims
 *  - no claims of live bank state (balances come from local ledger only)
 *  - no exposure of secrets, OTPs, full account numbers or unrelated SMS
 *  - no financial advice (budgeting suggestions are descriptive, never
 *    prescriptive about what the user SHOULD do with money)
 */
object AiSafetyPolicy {

    enum class Verdict { ALLOW, REFUSE }

    data class Decision(
        val verdict: Verdict,
        /** Machine-readable rule that fired; null when allowed. */
        val rule: Rule?,
        /** User-facing refusal text; null when allowed. */
        val message: String?,
    )

    enum class Rule {
        MONEY_MOVEMENT_REQUEST,
        BANK_LOGIN_OR_CREDENTIALS,
        INVESTMENT_ADVICE,
        LIVE_BANK_STATE_CLAIM,
        SECRET_EXPOSURE,
        FINANCIAL_ADVICE,
        OUT_OF_SCOPE,
    }

    /**
     * Evaluate a user request (or an AI-proposed action) against the policy.
     * Deterministic: same input always yields same decision.
     */
    fun evaluate(request: String): Decision {
        val q = request.trim().lowercase()
        if (q.isEmpty()) {
            return Decision(Verdict.ALLOW, null, null)
        }

        if (containsAny(q, listOf(
                "send money", "transfer money", "pay someone", "make a payment",
                "move funds", "withdraw and send", "execute transfer", "pay my bill",
                "pay my electricity bill", "pay the bill", "settle my bill",
            ))
        ) {
            return refuse(Rule.MONEY_MOVEMENT_REQUEST)
        }
        if (containsAny(q, listOf(
                "login", "log in", "password", "otp", "pin ", "credentials",
                "net banking", "netbanking", "card number", "cvv",
            ))
        ) {
            return refuse(Rule.BANK_LOGIN_OR_CREDENTIALS)
        }
        if (containsAny(q, listOf(
                "should i invest", "invest in", "buy stocks", "mutual fund advice",
                "crypto", "share market tip", "stock tip", "portfolio advice",
            ))
        ) {
            return refuse(Rule.INVESTMENT_ADVICE)
        }
        if (containsAny(q, listOf(
                "my real balance right now", "live balance", "current bank balance from bank",
                "check my bank directly", "fetch from bank",
            ))
        ) {
            return refuse(Rule.LIVE_BANK_STATE_CLAIM)
        }
        if (containsAny(q, listOf(
                "show me all sms", "read my messages", "forward otp", "export raw messages",
                "show passwords", "show my otps",
            ))
        ) {
            return refuse(Rule.SECRET_EXPOSURE)
        }
        if (containsAny(q, listOf(
                "should i spend", "should i buy", "afford to buy", "can i afford",
                "should i take a loan", "should i cancel",
            ))
        ) {
            return refuse(Rule.FINANCIAL_ADVICE)
        }
        if (containsAny(q, listOf(
                "weather", "news", "recipe", "joke", "translate",
            ))
        ) {
            return refuse(Rule.OUT_OF_SCOPE)
        }
        return Decision(Verdict.ALLOW, null, null)
    }

    private fun refuse(rule: Rule): Decision = Decision(
        verdict = Verdict.REFUSE,
        rule = rule,
        message = when (rule) {
            Rule.MONEY_MOVEMENT_REQUEST ->
                "I can't move money or execute payments. FinTrack only records and explains transactions you already made."
            Rule.BANK_LOGIN_OR_CREDENTIALS ->
                "I can't handle bank logins, OTPs, PINs or card numbers — and I'll never ask for them."
            Rule.INVESTMENT_ADVICE ->
                "Investment decisions are outside FinTrack's scope. I can show your recorded spending history instead."
            Rule.LIVE_BANK_STATE_CLAIM ->
                "Balances in FinTrack come from your locally recorded transactions and snapshots — not live bank state."
            Rule.SECRET_EXPOSURE ->
                "Raw SMS content may contain sensitive information; I only share redacted evidence for specific transactions."
            Rule.FINANCIAL_ADVICE ->
                "That's a personal financial decision. I can show you the relevant facts from your history, but the choice is yours."
            Rule.OUT_OF_SCOPE ->
                "That's outside FinTrack's scope — I can help with your recorded personal finance history."
        },
    )

    private fun containsAny(q: String, needles: List<String>): Boolean =
        needles.any { it in q }
}
