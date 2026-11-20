package com.expenses.app.ui.screens.you

import com.expenses.app.data.model.AccountType
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.app.ui.screens.onboarding.AccountItem
import com.expenses.app.ui.screens.signin.ActionProblem
import java.time.LocalDate
import java.time.LocalDateTime

/** Sample states of the You tab and its screens, one per design state, with the design's names and numbers. */
object YouSamples {
    /** design/spec/screens/04-you/00-you: Arjun, signed in, both switches on, tracking since 1 Nov 2026. */
    val default: YouUiState = YouUiState(
        profile = YouProfile(name = "Arjun", avatarColor = SelfAvatarColor),
        signedIn = true,
        smsTrackingOn = true,
        trackedAccountCount = 4,
        remindersOn = true,
        reminderDaysBefore = 3,
        trackingStartDate = LocalDate.of(2026, 11, 1),
        messagesRead = 214,
        messagesSent = 0,
        family = YouFamily.Together(name = "Home", me = "Arjun", partner = "Meera"),
    )

    /** design/spec/screens/04-you/06-you-not-signed-in: the same, not signed in, so no family on this phone. */
    val signedOut: YouUiState = default.copy(signedIn = false, family = YouFamily.None)

    /** Not drawn as a frame: the FAMILY row while an invite is out ("Home · waiting for your partner"). */
    val familyWaiting: YouUiState = default.copy(family = YouFamily.Waiting("Home"))

    /** Not drawn as a frame: the FAMILY row when the person is alone in the family ("Home · just you"). */
    val familyAlone: YouUiState = default.copy(family = YouFamily.Alone("Home"))

    /** Not drawn as a frame: the FAMILY row signed in with no family ("Share a monthly total with your partner"). */
    val familyNone: YouUiState = default.copy(family = YouFamily.None)

    /** design/spec/screens/04-you/02-account: Arjun, his phone number and UPI ID. */
    val account: YouAccountUiState = YouAccountUiState(name = "Arjun", phone = "+91 98765 43210", upiId = "arjun@okhdfc")

    /** design/spec/screens/04-you/07-account-upi-id-not-valid: the UPI ID has no "@". */
    val accountUpiInvalid: YouAccountUiState = account.copy(upiId = "arjunokhdfc", upiInvalid = true)

    /** design/spec/screens/04-you/03-delete-account-confirm. */
    val accountDelete: YouAccountUiState = account.copy(dialog = YouAccountDialog.DELETE)

    /** design/spec/screens/04-you/08-sign-out-changes-not-sent: two changes wait. */
    val accountUnsent: YouAccountUiState = account.copy(dialog = YouAccountDialog.UNSENT_CHANGES, unsentCount = 2)

    /** Not in the design: Save was tapped offline. */
    val accountOffline: YouAccountUiState = account.copy(problem = ActionProblem.OFFLINE)

    /** design/spec/screens/04-you/01-accounts-and-cards: four accounts tracked, SBI switched off. */
    val accounts: AccountsUiState = AccountsUiState(
        accounts = listOf(
            AccountItem(1, "HDFC Bank", null, AccountType.BANK_ACCOUNT, "4521", null, tracked = true),
            AccountItem(2, "ICICI Bank", "ICICI Amazon Pay", AccountType.CREDIT_CARD, "8890", null, tracked = true),
            AccountItem(3, "HDFC Bank", "HDFC Millennia", AccountType.CREDIT_CARD, "2217", null, tracked = true),
            AccountItem(4, null, null, AccountType.UPI_ID, null, "arjun@okhdfc", tracked = true),
            AccountItem(5, "SBI", null, AccountType.BANK_ACCOUNT, "0034", null, tracked = false),
        ),
        renaming = null,
    )

    /**
     * design/spec/screens/04-you/10-accounts-and-cards-rename: HDFC Bank being renamed, nothing typed yet, so the
     * field shows the bank's name as its hint.
     */
    val accountsRenaming: AccountsUiState = accounts.copy(renaming = RenameDraft(accountId = 1, text = ""))

    /** design/spec/screens/04-you/04-what-we-ve-read: 214 messages used, three latest, 1,902 skipped. */
    val whatWeRead: WhatWeReadUiState = WhatWeReadUiState(
        sentOffPhone = 0,
        bankMessagesRead = 214,
        latestUsed = listOf(
            UsedMessage(1, "HDFC Bank", UsedMessageKind.Debit, LocalDateTime.of(2026, 11, 16, 9, 12), 45_000),
            UsedMessage(2, "ICICI Bank", UsedMessageKind.Card, LocalDateTime.of(2026, 11, 16, 8, 40), 18_600),
            UsedMessage(3, "HDFC Bank", UsedMessageKind.Credit, LocalDateTime.of(2026, 11, 15, 18, 5), 120_000),
        ),
        today = LocalDate.of(2026, 11, 16),
        skipped = 1_902,
    )

    /** design/spec/screens/04-you/05-tracking-start-date: since 1 Nov 2026, Last 3 months chosen. */
    val trackingStart: TrackingStartUiState = TrackingStartUiState(
        today = LocalDate.of(2026, 11, 16),
        currentStart = LocalDate.of(2026, 11, 1),
        thisMonth = LocalDate.of(2026, 11, 1),
        lastThreeMonths = LocalDate.of(2026, 9, 1),
        pickedDate = null,
        selected = StartChoice.LastThreeMonths,
        smsTrackingOn = true,
        scan = OlderScanStatus.Idle,
    )

    /** Tracking start date while the older messages are read. */
    val trackingStartScanning: TrackingStartUiState = trackingStart.copy(
        scan = OlderScanStatus.Running(read = 120, total = 480),
    )
}
