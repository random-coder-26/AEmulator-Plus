package app.aemu.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Partitions the importer did not know by name: they are taken as they are given, under their own name. */
class ExtraPartitionsTest {
    private fun lp(vararg names: String) = names.map { LpMetadata.Partition(it, listOf(LpMetadata.Extent(0, 4096))) }

    @Test fun superKeepsEveryLogicalPartitionKnownOnesFirst() {
        val picks = LpMetadata.pick(lp("cust_a", "my_stock_a", "system_a", "odm_a", "vendor_a", "system_dlkm_a", "cust_b", "system_b"))
        assertEquals(listOf("system_a", "vendor_a", "odm_a", "cust_a", "my_stock_a", "system_dlkm_a"), picks.map { it.name })
    }

    @Test fun superSlotBIsUsedOnlyWithoutSlotAOrPlainName() {
        assertEquals(listOf("system_b", "cust_b"), LpMetadata.pick(lp("system_b", "cust_b")).map { it.name })
        assertEquals(listOf("system", "cust"), LpMetadata.pick(lp("cust", "system", "cust_b")).map { it.name })
    }

    @Test fun logicalPartitionMountsAtItsOwnName() {
        assertEquals("cust", PartitionNames.logicalMount("cust"))
        assertEquals("my_stock", PartitionNames.logicalMount("my_stock"))
        assertEquals("vendor_dlkm", PartitionNames.logicalMount("Vendor_Dlkm"))
        assertEquals("system/product", PartitionNames.logicalMount("product"))
        assertEquals("system/system_ext", PartitionNames.logicalMount("system_ext"))
    }

    @Test fun logicalPartitionNamesTheRootCannotHoldAreRefused() {
        for (n in listOf("data", "dev", "proc", "sys", "system", "vendor", "boot", "sdcard", "../x", "a/b", "", "9lives", "x".repeat(60)))
            assertNull(n, PartitionNames.logicalMount(n))
    }

    @Test fun namedExtraImages() {
        for ((n, mount) in listOf("cust.img" to "cust", "cust_a.img" to "cust", "preload.img.lz4" to "preload", "carrier.ext4.img" to "carrier",
            "hw_product.img" to "hw_product", "system_dlkm.erofs.img" to "system_dlkm", "odm.img" to "odm", "product.img" to "system/product"))
            assertEquals(n, mount, PartitionNames.extraMount(n))
        // the inactive slot and look-alikes are not images of those partitions
        for (n in listOf("cust_b.img", "custom.img", "cust.xml", "preload_config.bin")) assertNull(n, PartitionNames.extraMount(n))
    }

    @Test fun gptPicksEveryNamedExtraPartitionOncePerMountPoint() {
        val parts = listOf("boot_a", "system_a", "cust", "preload_a", "preload_b", "modem", "persist", "my_stock").map { GptDisk.Part(it, 0, 4096) }
        val picks = GptDisk.pick(parts)
        assertEquals(listOf("boot_a", "system_a", "cust", "preload_a", "my_stock"), picks.map { it.second.name })
        assertEquals(GptDisk.Kind.EXTRA, picks.last().first)
    }
}
