package com.expenses.app.ui.screens.onboarding

import com.expenses.app.data.model.AccountType
import java.time.LocalDate

/** Sample states of the onboarding screens, with the values the designs show, for previews and screenshots. */
object OnboardingSamples {
    private val firstOfNovember: LocalDate = LocalDate.of(2026, 11, 1)

    private val hdfcSavings = AccountItem(
        id = 1,
        bank = "HDFC Bank",
        displayName = null,
        type = AccountType.BANK_ACCOUNT,
        last4 = "4521",
        upiId = null,
        tracked = true,
    )
    private val iciciCard = AccountItem(
        id = 2,
        bank = "ICICI Amazon Pay",
        displayName = null,
        type = AccountType.CREDIT_CARD,
        last4 = "8890",
        upiId = null,
        tracked = true,
    )
    private val hdfcCard = AccountItem(
        id = 3,
        bank = "HDFC Millennia",
        displayName = null,
        type = AccountType.CREDIT_CARD,
        last4 = "2217",
        upiId = null,
        tracked = true,
    )
    private val upi = AccountItem(
        id = 4,
        bank = null,
        displayName = null,
        type = AccountType.UPI_ID,
        last4 = null,
        upiId = "arjun@okhdfc",
        tracked = true,
    )
    private val sbiSavings = AccountItem(
        id = 5,
        bank = "SBI",
        displayName = null,
        type = AccountType.BANK_ACCOUNT,
        last4 = "0034",
        upiId = null,
        tracked = false,
    )

    /** Your name with "Arjun" typed (04-your-name). */
    val yourName: YourNameUiState = YourNameUiState(name = "Arjun")

    /** How far back with This month chosen (08-how-far-back). */
    val howFarBack: HowFarBackUiState = HowFarBackUiState(
        selected = StartChoice.ThisMonth,
        thisMonthStart = firstOfNovember,
        threeMonthsStart = LocalDate.of(2026, 9, 1),
        pickedDate = null,
        today = firstOfNovember.plusDays(15),
    )

    /** Scanning SMS part way through, with three accounts found (09-scanning-sms). */
    val scanning: ScanningUiState = ScanningUiState(
        since = firstOfNovember,
        read = 132,
        total = 214,
        found = listOf(
            ScannedAccount(hdfcSavings, payments = 38),
            ScannedAccount(iciciCard, payments = 21),
            ScannedAccount(upi, payments = 56),
        ),
    )

    /** Scanning SMS just started, before the inbox was counted. */
    val scanningStarted: ScanningUiState = ScanningUiState(since = firstOfNovember)

    /** Nothing found after reading since 1 Nov (10-nothing-found). */
    val nothingFound: NothingFoundUiState = NothingFoundUiState(since = firstOfNovember)

    /** Confirm accounts with five accounts, SBI switched off (11-confirm-accounts). */
    val confirmAccounts: ConfirmAccountsUiState = ConfirmAccountsUiState(
        accounts = listOf(hdfcSavings, iciciCard, hdfcCard, upi, sbiSavings),
    )

    /** Confirm accounts with the rename dialog open on HDFC Bank. */
    val renameAccount: ConfirmAccountsUiState = confirmAccounts.copy(
        renaming = RenameDraft(hdfcSavings, text = "Salary account"),
    )
}
