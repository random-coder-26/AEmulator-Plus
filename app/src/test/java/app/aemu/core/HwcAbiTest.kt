package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HwcAbi: the stride of hwc_layer_1_t as libsurfaceflinger.so's HWComposer::createWorkList uses it (bytes of the real functions) */
class HwcAbiTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Nexus 4 JVP15Q (Android 4.2, AOSP SurfaceFlinger), 264 bytes, Thumb-2
    private val aosp = bytes(
        "1f292de9ff410746164672d84ff00040d7f8d83030fa01f21a426ad06ee04ff0380404fb01f8374908f1180807eb0804" +
        "fff716fc00b10136616ab14201d3a26a4ab9a06af9f7b6ed602070431430f9f7b8ed6662a062b8682a49fff701fc0028" +
        "3cd0a16a6022751e02fb051000216d461430e062f9f71ceb002110226846f9f718ebe36a57f80800032762681f600023" +
        "e16a029003924b60e06a8360e26a276bd76040f20512e16a0b61e06a42619de80f00e76a183787e80f00e76a283787e8" +
        "0f000121e36a99634ff0ff33e06a00f12802c263e16a0b64e06a4364a36a4ff0ff3101221960a06ac2600020a36a1e61" +
        "01e06ff04a0004b0bde8f081b86800288dd1f8e701000101")

    // BLU Life One (MT6589, Android 4.2.1, MediaTek's SurfaceFlinger with "[MTK HWC part]"), 308 bytes, Thumb-2
    private val mtk = bytes(
        "1f292de9f043054685b0164600f285804ff00040d5f8d83030fa01f21a427cd080e04ff0380404fb01f8414908f11808" +
        "05eb0804fff7a6fb00b10136616ab14201d3a26a4ab9a06af9f7d8e8e02070431430f9f7dae86662a062a8683449fff7" +
        "91fb00284ed0a76ae022701e0021e94602fb00701430e062f8f714ee002110226846f8f710eee76a032055f808306168" +
        "38600020e26a029303915060e36a9860e16a276bcf6040f20511e26a1061e36a59619de80f00e76a183787e80f00e76a" +
        "283787e80f000122e06a8263e36a03f12801d9634ff0ff33e06a0364e26a5364e16a4b66e06a8366e26a55f80810d166" +
        "e36a60681867e26a55f808105167e36aa0689867a26a4ff0ff30012110600020a36ad960a26a166101e06ff04a0005b0" +
        "bde8f083a86800287ff47baff7e700bf01000101")

    @Test fun aospSurfaceFlingerUsesTheStandInsStride() {
        assertEquals(HwcAbi.STAND_IN_LAYER_SIZE, HwcAbi.layerSizeIn(aosp, 0, aosp.size, thumb = true))
    }

    @Test fun mediatekSurfaceFlingerUsesALongerLayer() {
        assertEquals(224, HwcAbi.layerSizeIn(mtk, 0, mtk.size, thumb = true))
    }

    @Test fun armCodeIsReadToo() {
        // mov r0,#0x60 ; mul r0,r6,r0 ; add r0,r0,#0x14
        val code = bytes("6000a0e3" + "960000e0" + "140080e2")
        assertEquals(96, HwcAbi.layerSizeIn(code, 0, code.size, thumb = false))
    }

    @Test fun unknownCodeGivesNothing() {
        assertNull(HwcAbi.layerSizeIn(ByteArray(64), 0, 64, thumb = true))
        assertNull(HwcAbi.layerSizeIn(ByteArray(64), 0, 64, thumb = false))
        // the Thumb function read as ARM is no match either
        assertNull(HwcAbi.layerSizeIn(aosp, 0, aosp.size, thumb = false))
    }
}
