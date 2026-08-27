package com.example.fintrack.parser.fixture

import com.example.fintrack.parser.BorderlineReason
import com.example.fintrack.parser.CreditKind
import com.example.fintrack.parser.Direction
import com.example.fintrack.parser.FinancialClass
import com.example.fintrack.parser.Rail
import com.example.fintrack.parser.classify.DeterministicSmsClassifier

/**
 * Golden fixture corpus (module 141).
 *
 * Structure: each fixture is (id, raw SMS, expected classification, optional
 * expected extraction assertions). The corpus covers multiple Indian banks,
 * UPI, cards, salary, interest, refunds/cashback, P2P and transfers, plus
 * malformed/ambiguous cases that must stay unresolved.
 *
 * Adding a bank = adding fixtures here + (if needed) one adapter rule; see
 * docs/parser-authoring.md for the guide.
 */
object FixtureCorpus {

    const val VERSION = "fixtures-v2"

    data class Fixture(
        val id: String,
        val raw: String,
        val expectedClass: FinancialClass,
        val expectedBorderlineReason: BorderlineReason? = null,
        // Optional extraction expectations; null = not asserted.
        val expectParsed: Boolean? = null,
        val amountMinor: Long? = null,
        val direction: Direction? = null,
        val rail: Rail? = null,
        val upiVpa: String? = null,
        val bankReference: String? = null,
        val cardMask: String? = null,
        val creditKind: CreditKind? = null,
        /** Human-readable note on why this fixture exists (regression context). */
        val note: String? = null,
    )

    val ALL: List<Fixture> = listOf(
        // ---- UPI ----
        Fixture(
            "upi-debit-hdfc", "Rs.250.00 debited from A/c XX1234 on 15/07/26 at 14:32 to Swiggy " +
                "(swiggy@ybl) via UPI. Ref 418293746512", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 25_000L, direction = Direction.DEBIT,
            rail = Rail.UPI, upiVpa = "swiggy@ybl", bankReference = "418293746512",
        ),
        Fixture(
            "upi-credit-p2p", "INR 1,500 credited to your A/c XX5678 on 16-07-2026 at 09:10 from " +
                "Rahul Sharma (rahul.sharma@okhdfcbank). UPI Ref 512340987654", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 150_000L, direction = Direction.CREDIT,
            rail = Rail.UPI, upiVpa = "rahul.sharma@okhdfcbank", creditKind = CreditKind.P2P_RECEIVE,
        ),
        Fixture(
            "upi-paytm-handle", "Paid Rs.99/- via UPI to Netflix (netflix@paytm) on 01/08/26. " +
                "Ref 771234567890", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 9_900L, direction = Direction.DEBIT, rail = Rail.UPI,
            upiVpa = "netflix@paytm",
        ),

        // ---- IMPS / NEFT / RTGS ----
        Fixture(
            "imps-transfer-out", "IMPS transfer of Rs.15,000.00 from A/c XX1234 to VENDOR LLP " +
                "on 02/08/26. Ref no UTIB123456789", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 1_500_000L, direction = Direction.DEBIT, rail = Rail.IMPS,
        ),
        Fixture(
            "neft-salary", "NEFT credit Rs.85,000/- A/c XX1234 SALARY AUGUST ABC TECHNOLOGIES " +
                "on 01-08-2026. UTR N123456789012345", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 8_500_000L, direction = Direction.CREDIT,
            rail = Rail.NEFT, creditKind = CreditKind.SALARY, bankReference = "N123456789012345",
        ),
        Fixture(
            "rtgs-in", "RTGS credit of INR 5,00,000.00 in A/c XX9999 on 03/08/26. UTR " +
                "HDFC202608031", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 50_000_000L, direction = Direction.CREDIT,
            rail = Rail.RTGS, creditKind = CreditKind.TRANSFER_IN,
        ),

        // ---- cards ----
        Fixture(
            "card-pos-icici", "ICICI Card XX4411 spent Rs.1,299.00 at AMAZON PAY INDIA on " +
                "04/08/26 at 18:45", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 129_900L, direction = Direction.DEBIT,
            rail = Rail.CARD_POS, cardMask = "4411",
        ),
        Fixture(
            "card-online-sbi", "SBI Card ending 8823 used for online purchase of Rs.450/- on " +
                "05/08/26. Ref 220855123456", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 45_000L, direction = Direction.DEBIT,
            rail = Rail.CARD_ONLINE, cardMask = "8823",
        ),

        // ---- interest / cashback / refund ----
        Fixture(
            "interest-credit", "Interest credited Rs.132.50 to your savings A/c XX1234 on " +
                "30/06/26", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 13_250L, direction = Direction.CREDIT,
            creditKind = CreditKind.INTEREST_CREDIT,
        ),
        Fixture(
            "cashback-credit", "Cashback of Rs.50/- credited to A/c XX1234 on 06/08/26. " +
                "Ref 660123456789", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 5_000L, direction = Direction.CREDIT,
            creditKind = CreditKind.CASHBACK,
        ),
        Fixture(
            "refund-credit", "Refund of Rs.2,499.00 credited to A/c XX1234 on 07/08/26 from " +
                "Myntra. Ref 770987654321", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 249_900L, direction = Direction.CREDIT,
            creditKind = CreditKind.REFUND,
        ),

        // ---- ATM ----
        Fixture(
            "atm-withdrawal", "Rs.5,000.00 withdrawn from A/c XX1234 ATM HDFC ANDHERI on " +
                "08/08/26 at 21:10", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 500_000L, direction = Direction.DEBIT, rail = Rail.ATM,
        ),

        // ---- non-financial ----
        Fixture(
            "otp-noise", "Your OTP for login is 482913. Do not share with anyone.", 
            FinancialClass.NON_FINANCIAL,
        ),
        Fixture(
            "marketing", "Congratulations! Get 20% discount on your next order. Click here.",
            FinancialClass.NON_FINANCIAL,
        ),
        Fixture(
            "kyc-notice", "Complete your KYC before 31st August to keep your wallet active.",
            FinancialClass.NON_FINANCIAL,
        ),

        // ---- borderline: must NOT be parsed deterministically ----
        Fixture(
            "borderline-amount-no-verb", "Avail balance in A/c XX1234 is Rs.12,345.67 as on " +
                "09/08/26", FinancialClass.BORDERLINE,
            BorderlineReason.AMOUNT_WITHOUT_VERB, expectParsed = false,
        ),
        Fixture(
            "borderline-verb-no-amount", "Amount debited from A/c XX1234 on 14/08/26. " +
                "Check the app for details.", FinancialClass.BORDERLINE,
            BorderlineReason.VERB_WITHOUT_AMOUNT, expectParsed = false,
        ),

        // ---- malformed / ambiguous: financial-looking but not extractable ----
        Fixture(
            "malformed-amount-comma", "Debited Rs.,,, from account on 10/08/26",
            FinancialClass.BORDERLINE, expectParsed = false,
        ),
        Fixture(
            "malformed-vpa-two-at", "Rs.100 debited to foo@@bar via UPI on 11/08/26",
            FinancialClass.FINANCIAL, expectParsed = false, // VPA invalid -> adapter refuses
        ),
        Fixture(
            "ambiguous-direction", "Transaction of Rs.500 on A/c XX1234 dated 12/08/26",
            FinancialClass.BORDERLINE, expectParsed = false, // no economic verb
        ),

        // ---- Stage 12 P25 #3: expanded edge-case fixtures ----

        // EMI / loan
        Fixture(
            "emi-debit-hdfc", "EMI of Rs.12,500.00 debited from A/c XX1234 on 05/08/26 for " +
                "Home Loan A/c HL987654. Ref 880123456789", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 1_250_000L, direction = Direction.DEBIT,
            note = "EMI debit with loan account reference",
        ),
        Fixture(
            "emi-credit-card", "EMI conversion: Rs.45,000.00 converted to 12 EMIs of " +
                "Rs.3,750.00 on your HDFC Card XX4411. First EMI debited on 10/08/26",
            FinancialClass.FINANCIAL, expectParsed = true, amountMinor = 4_500_000L,
            direction = Direction.DEBIT, rail = Rail.CARD_POS, cardMask = "4411",
            note = "EMI conversion notice — parser extracts amount, direction, card mask",
        ),

        // Refund edge cases
        Fixture(
            "refund-partial", "Partial refund of Rs.1,250.00 credited to A/c XX1234 on 08/08/26 " +
                "for order #12345. Ref 881234567890", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 125_000L, direction = Direction.CREDIT,
            creditKind = CreditKind.REFUND,
            note = "Partial refund — must not be confused with full refund",
        ),
        Fixture(
            "refund-upi", "Refund of Rs.499.00 credited to your UPI A/c XX1234 on 09/08/26 " +
                "from Swiggy. UPI Ref 882345678901", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 49_900L, direction = Direction.CREDIT,
            rail = Rail.UPI, creditKind = CreditKind.REFUND,
        ),

        // Transfer edge cases
        Fixture(
            "transfer-own-account", "Transfer of Rs.10,000.00 from A/c XX1234 to A/c XX5678 " +
                "on 10/08/26. Ref 883456789012", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 1_000_000L, direction = Direction.DEBIT,
            note = "Own-account transfer — must be excluded from income/expense",
            bankReference = "883456789012",
            rail = Rail.UNKNOWN,
        ),
        Fixture(
            "transfer-neft-in", "NEFT credit Rs.25,000/- A/c XX1234 from SELF TRANSFER " +
                "on 11/08/26. UTR N987654321098765", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 2_500_000L, direction = Direction.CREDIT,
            rail = Rail.NEFT, creditKind = CreditKind.TRANSFER_IN,
        ),

        // Card edge cases
        Fixture(
            "card-atm-withdrawal", "Rs.2,000.00 withdrawn from ICICI Card XX4411 at ATM " +
                "ANDHERI on 12/08/26 at 22:15", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 200_000L, direction = Direction.DEBIT,
            rail = Rail.ATM, cardMask = "4411",
            note = "Card ATM withdrawal — rail is ATM, not CARD_POS",
        ),
        Fixture(
            "card-international", "SBI Card ending 8823 used for international purchase of " +
                "USD 25.00 (Rs.2,075.00) on 13/08/26. Ref 884567890123", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 207_500L, direction = Direction.DEBIT,
            rail = Rail.CARD_POS, cardMask = "8823",
            note = "International card purchase — no online/ecom marker so the " +
                "deterministic parser stays at CARD_POS (never guessed)",
        ),

        // UPI edge cases
        Fixture(
            "upi-mandate", "UPI mandate of Rs.999.00 created for Netflix (netflix@paytm) " +
                "on 14/08/26. Ref 885678901234", FinancialClass.BORDERLINE,
            expectParsed = false, // mandate creation, not a transaction
            note = "UPI mandate creation — not a transaction, classified as BORDERLINE",
        ),
        Fixture(
            "upi-collect", "Collect request of Rs.500.00 from Rahul (rahul@okhdfcbank) " +
                "on 15/08/26. Approve in your UPI app.", FinancialClass.BORDERLINE,
            expectParsed = false, // collect request, not a completed transaction
            note = "UPI collect request — requires user action",
        ),

        // Recurring / subscription
        Fixture(
            "recurring-netflix", "Rs.649.00 debited from A/c XX1234 on 16/08/26 for Netflix " +
                "subscription. Ref 886789012345", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 64_900L, direction = Direction.DEBIT,
            note = "Recurring subscription — same amount each month",
        ),
        Fixture(
            "recurring-sip", "SIP of Rs.5,000.00 debited from A/c XX1234 on 17/08/26 for " +
                "HDFC Mutual Fund. Ref 887890123456", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 500_000L, direction = Direction.DEBIT,
            note = "SIP investment — recurring debit",
        ),

        // Ambiguous / conflicting Indian messages
        Fixture(
            "ambiguous-two-amounts", "Rs.250 debited from A/c XX1234 on 18/08/26. " +
                "Available balance Rs.2,550.00", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 25_000L, direction = Direction.DEBIT,
            note = "Two amounts in one message — first is the transaction, second is balance",
        ),
        Fixture(
            "ambiguous-hinglish", "A/c XX1234 se Rs.1,500 ka payment hua hai on 19/08/26. " +
                "Ref 889012345678", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 150_000L, direction = Direction.DEBIT,
            note = "Hinglish message — 'se' and 'ka payment hua' are Hindi markers",
        ),
        Fixture(
            "ambiguous-no-account", "Rs.750 debited on 20/08/26. Ref 890123456789",
            FinancialClass.FINANCIAL, expectParsed = true, amountMinor = 75_000L,
            direction = Direction.DEBIT,
            note = "No account token — parser must still extract amount and direction",
        ),
        Fixture(
            "ambiguous-future-dated", "Rs.1,000 will be debited from A/c XX1234 on 25/08/26 " +
                "for scheduled payment. Ref 891234567890", FinancialClass.BORDERLINE,
            expectParsed = false, // future-dated, not yet occurred
            note = "Future-dated scheduled payment — not yet a transaction",
        ),

        // ---- Fee / charge ----
        Fixture(
            "imps-charge", "IMPS charge of Rs.5.00 debited from A/c XX1234 on 20/08/26. " +
                "Ref 900123456789", FinancialClass.FINANCIAL,
            expectParsed = true, amountMinor = 500L, direction = Direction.DEBIT,
            rail = Rail.IMPS, bankReference = "900123456789",
            note = "IMPS charge — feeAmountMinor should be extracted as 500",
        ),
    )
}
