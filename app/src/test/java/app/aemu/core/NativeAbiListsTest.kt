package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeAbiListsTest {
    @Test fun armKeepsTheListsItAlwaysHad() {
        assertEquals(NativeAbiLists.Lists("arm64-v8a,armeabi-v7a,armeabi", "arm64-v8a", "armeabi-v7a,armeabi"), NativeAbiLists.forArch(GuestArch.ARM, true))
        assertEquals(NativeAbiLists.Lists("arm64-v8a", "arm64-v8a", ""), NativeAbiLists.forArch(GuestArch.ARM, false))
    }

    // the crash this fixes: an x86-64 firmware told "arm64-v8a" -> ExtServices / NetworkStack fail with -113 -> system_server dies
    @Test fun x86_64FirmwareNamesItsOwnAbis() {
        assertEquals(NativeAbiLists.Lists("x86_64,x86", "x86_64", "x86"), NativeAbiLists.forArch(GuestArch.X86_64, true))
        assertEquals(NativeAbiLists.Lists("x86_64", "x86_64", ""), NativeAbiLists.forArch(GuestArch.X86_64, false))
    }

    @Test fun foreignListsNeverOfferArmAbis() {
        for (arch in listOf(GuestArch.X86, GuestArch.X86_64)) for (has32 in listOf(true, false)) {
            val l = NativeAbiLists.forArch(arch, has32)
            assertFalse(l.abilist, l.abilist.contains("arm"))
            assertFalse(l.abilist64, l.abilist64.contains("arm"))
            assertFalse(l.abilist32, l.abilist32.contains("arm"))
        }
    }

    @Test fun x86HasNoSixtyFourBitSide() {
        assertEquals(NativeAbiLists.Lists("x86", "", "x86"), NativeAbiLists.forArch(GuestArch.X86, true))
    }

    @Test fun pure32TreeFallbacks() {
        assertEquals("armeabi-v7a,armeabi", NativeAbiLists.pure32Fallback(GuestArch.ARM))
        assertEquals("x86", NativeAbiLists.pure32Fallback(GuestArch.X86))
        assertEquals("armeabi-v7a,armeabi", NativeAbiLists.pure32FromAbi(GuestArch.ARM, "armeabi-v7a"))
        assertEquals("armeabi-v7a,armeabi", NativeAbiLists.pure32FromAbi(GuestArch.ARM, "armeabi"))
        assertEquals("x86", NativeAbiLists.pure32FromAbi(GuestArch.X86, "x86"))
    }
}
