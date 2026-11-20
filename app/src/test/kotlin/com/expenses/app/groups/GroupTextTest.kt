package com.expenses.app.groups

import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.UpiIdInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTextTest {
    @Test fun phoneNumbersBecomeE164() {
        assertEquals("+919876543210", GroupText.normalizePhone("+91 98765 43210"))
        assertEquals("+919876543210", GroupText.normalizePhone("98765-43210"))
        assertEquals("+919876543210", GroupText.normalizePhone("098765 43210"))
        assertEquals("+919876543210", GroupText.normalizePhone("91 9876543210"))
        assertEquals("+14155552671", GroupText.normalizePhone("+1 (415) 555-2671"))
        assertNull(GroupText.normalizePhone("12345"))
        assertNull(GroupText.normalizePhone(""))
        assertTrue(GroupText.isPhoneOk(""))
        assertFalse(GroupText.isPhoneOk("call me"))
    }

    @Test fun splitDescriptionsNeverCarryAUpiIdOrALongNumber() {
        assertEquals("Swiggy", GroupText.descriptionFor("Swiggy", "Food"))
        assertEquals("Food", GroupText.descriptionFor("9876543210@ybl", "Food"))
        assertEquals("Food", GroupText.descriptionFor("rahul.sharma@okhdfc", "Food"))
        assertEquals("Other", GroupText.descriptionFor("IMPS 123456 transfer", "Other"))
        assertEquals("Other", GroupText.descriptionFor(null, "Other"))
        assertEquals("Flat 302 rent", GroupText.descriptionFor("Flat 302 rent", "Rent and bills"))
    }

    @Test fun textIsTrimmedCutAndCleaned() {
        assertEquals("Goa", GroupText.clean("  Goa \u0007 ", 40))
        assertEquals("abc", GroupText.clean("⁦abc⁩", 40))
        assertEquals(40, GroupText.clean("x".repeat(100), GroupText.MAX_GROUP_NAME).length)
    }

    @Test fun upiIdsFollowTheServersRule() {
        assertEquals(UpiIdInput.Valid("name@okaxis"), GroupText.checkUpiId("  name@okaxis "))
        assertEquals(UpiIdInput.Valid("98xxxxxx10@ybl"), GroupText.checkUpiId("98xxxxxx10@ybl"))
        assertEquals(UpiIdInput.Valid("Arjun.Example-1@OKHDFC"), GroupText.checkUpiId("Arjun.Example-1@OKHDFC"))
        assertEquals(UpiIdInput.None, GroupText.checkUpiId("   "))
        assertEquals(UpiIdInput.None, GroupText.checkUpiId(null))
        assertEquals(UpiIdInput.Invalid, GroupText.checkUpiId("arjunokhdfc"))
        assertEquals(UpiIdInput.Invalid, GroupText.checkUpiId("a@okaxis"))
        assertEquals(UpiIdInput.Invalid, GroupText.checkUpiId("name@ok axis"))
        assertEquals(UpiIdInput.Invalid, GroupText.checkUpiId("name+1@okaxis"))
        assertEquals(UpiIdInput.Invalid, GroupText.checkUpiId("name@@okaxis"))
        assertEquals(UpiIdInput.Invalid, GroupText.checkUpiId("name@1bank"))
        assertTrue(GroupText.isPayableUpiId("rahul@okhdfc"))
        assertFalse(GroupText.isPayableUpiId(null))
        assertFalse(GroupText.isPayableUpiId(" rahul@okhdfc"))
    }
}
