package com.expenses.app.data.category

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryGuesserTest {
    @Test fun foodDelivery() {
        assertEquals(Category.FOOD, CategoryGuesser.guess("Swiggy"))
        assertEquals(Category.FOOD, CategoryGuesser.guess("ZOMATO LTD"))
    }

    @Test fun groceries() {
        assertEquals(Category.GROCERIES, CategoryGuesser.guess("Blinkit"))
        assertEquals(Category.GROCERIES, CategoryGuesser.guess("BigBasket"))
        assertEquals(Category.GROCERIES, CategoryGuesser.guess("Zepto Marketplace"))
    }

    @Test fun groceriesWinOverFoodForInstamart() = assertEquals(Category.GROCERIES, CategoryGuesser.guess("Swiggy Instamart"))

    @Test fun travel() {
        assertEquals(Category.TRAVEL, CategoryGuesser.guess("Uber India"))
        assertEquals(Category.TRAVEL, CategoryGuesser.guess("OLA CABS"))
        assertEquals(Category.TRAVEL, CategoryGuesser.guess("Rapido"))
        assertEquals(Category.TRAVEL, CategoryGuesser.guess("IRCTC"))
        assertEquals(Category.TRAVEL, CategoryGuesser.guess("Indian Oil fuel station"))
    }

    @Test fun rentAndBills() {
        assertEquals(Category.RENT_AND_BILLS, CategoryGuesser.guess("BESCOM electricity"))
        assertEquals(Category.RENT_AND_BILLS, CategoryGuesser.guess("Airtel recharge"))
        assertEquals(Category.RENT_AND_BILLS, CategoryGuesser.guess("ACT Fibernet broadband"))
        assertEquals(Category.RENT_AND_BILLS, CategoryGuesser.guess("House rent"))
    }

    @Test fun shopping() {
        assertEquals(Category.SHOPPING, CategoryGuesser.guess("Amazon"))
        assertEquals(Category.SHOPPING, CategoryGuesser.guess("Flipkart Internet"))
        assertEquals(Category.SHOPPING, CategoryGuesser.guess("Myntra"))
    }

    @Test fun fun_() {
        assertEquals(Category.FUN, CategoryGuesser.guess("Netflix"))
        assertEquals(Category.FUN, CategoryGuesser.guess("BookMyShow"))
        assertEquals(Category.FUN, CategoryGuesser.guess("Spotify India"))
        assertEquals(Category.FUN, CategoryGuesser.guess("Amazon Prime Video"))
    }

    @Test fun wholeWordsOnly() {
        assertEquals(Category.OTHER, CategoryGuesser.guess("Motorola"))
        assertEquals(Category.OTHER, CategoryGuesser.guess("Parental care"))
    }

    @Test fun fallsBackToVpa() = assertEquals(Category.FOOD, CategoryGuesser.guess("Bundl Technologies", "swiggy@icici"))

    @Test fun vpaHandlesThatRunWordsTogether() {
        assertEquals(Category.TRAVEL, CategoryGuesser.guess(null, "uberindia.rides@hdfcbank"))
        assertEquals(Category.RENT_AND_BILLS, CategoryGuesser.guess(null, "airtelrecharge@icici"))
    }

    @Test fun shortKeywordsInVpaNeedTheWholeWord() {
        assertEquals(Category.OTHER, CategoryGuesser.guess(null, "vikram.s@okaxis"))
        assertEquals(Category.OTHER, CategoryGuesser.guess(null, "olaf88@ybl"))
    }

    @Test fun vpaBankHandleIsIgnored() = assertEquals(Category.OTHER, CategoryGuesser.guess(null, "rahul.example88@airtel"))

    @Test fun unknownIsOther() {
        assertEquals(Category.OTHER, CategoryGuesser.guess("Rahul Menon"))
        assertEquals(Category.OTHER, CategoryGuesser.guess(null))
        assertEquals(Category.OTHER, CategoryGuesser.guess(""))
    }

    @Test fun merchantKeyIgnoresCaseAndPunctuation() {
        assertEquals("swiggy order", CategoryGuesser.merchantKey("SWIGGY*Order."))
        assertEquals(CategoryGuesser.merchantKey("swiggy order"), CategoryGuesser.merchantKey("Swiggy  -  ORDER"))
    }
}
