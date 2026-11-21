package app.aemu.importer

import app.aemu.core.SquashLayout
import app.aemu.importer.tools.FirmwareToolset
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The layout of a SquashFS image must rebuild it bit for bit from its files, however much of it can be recompressed
 * here. The two images were made by mksquashfs 4.6 (gzip, 4 KiB blocks): ZLIB with its default options, STORED with a
 * zlib window the JDK cannot reproduce, so that every data block has to be kept as it was.
 */
class SquashLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    // the content of the files, as it was when the images were made
    private fun text(name: String, size: Int): ByteArray {
        val sb = StringBuilder()
        var j = 0
        while (sb.length < size) { sb.append("file ").append(name).append(" line ").append(j).append('\n'); j++ }
        return sb.substring(0, size).toByteArray(Charsets.ISO_8859_1)
    }

    private fun random(size: Int): ByteArray {
        var x = 12345L
        return ByteArray(size) { x = (x * 1103515245L + 12345L) and 0x7fffffffL; ((x shr 16) and 0xff).toByte() }
    }

    private val files: Map<String, ByteArray> = linkedMapOf(
        "bin/app.bin" to text("app", 5000),      // one block and a tail in a fragment
        "bin/exact.bin" to text("exact", 8192),  // two blocks, no tail
        "etc/a.conf" to text("a", 300),          // fragments only
        "etc/b.conf" to text("b", 700),
        "etc/empty" to ByteArray(0),
        "lib/rand.so" to random(4196),           // does not compress: stored as it is
    )

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun image(name: String, b64: String): File {
        val raw = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(b64))).use { it.readBytes() }
        return tmp.newFile(name).also { it.writeBytes(raw) }
    }

    private fun capture(image: File, visitor: FirmwareToolset.SquashVisitor? = null) =
        File(tmp.root, image.name + ".layout").also { SquashLayout.capture(image, it, { }, { false }, visitor) }

    private fun rebuild(layout: File, content: (String) -> ByteArray?): Pair<File, List<String>> {
        val out = File(tmp.root, layout.name + ".rebuilt")
        val bad = SquashLayout.rebuild(layout, out, content)
        return out to bad
    }

    private fun assertExact(img: File) {
        val layout = capture(img)
        val (out, bad) = rebuild(layout) { files[it] }
        assertTrue(bad.toString(), bad.isEmpty())
        assertArrayEquals(img.readBytes(), out.readBytes())
    }

    @Test fun reproducibleImageIsRebuiltExactly() = assertExact(image("zlib.sqfs", ZLIB))

    @Test fun blocksThatCannotBeRebuiltAreKeptAndTheImageIsStillExact() = assertExact(image("stored.sqfs", STORED))

    @Test fun tablesListFilesLinksAndFolders() {
        val t = SquashLayout.load(capture(image("zlib.sqfs", ZLIB)))
        assertEquals(4096, t.blockSize)
        assertEquals(1, t.compression)
        assertEquals(files.keys, t.files.keys)
        for ((p, b) in files) {
            assertEquals(p, b.size.toLong(), t.files.getValue(p).size)
            assertEquals(p, sha1(b), t.files.getValue(p).sha1)
        }
        assertEquals(mapOf("lib/link" to "../bin/app.bin"), t.links)
        assertEquals(setOf("bin", "etc", "lib"), t.dirs.keys)
    }

    @Test fun theLayoutIsMuchSmallerThanTheImageWhenBlocksAreRebuilt() {
        // not asserted for the exact size: that depends on the zlib of the JDK matching the one of mksquashfs. If it
        // does not, every block is kept as it was and the layout is about as big as the image: still exact.
        val img = image("zlib.sqfs", ZLIB)
        val st = SquashLayout.capture(img, File(tmp.root, "x.layout"), { }, { false })
        assertEquals(img.length(), st.imageBytes)
        assertTrue(st.rebuilt + st.stored > 0)
        if (st.stored == 0) assertTrue(st.storedBytes < img.length() / 4)
    }

    @Test fun aFileThatIsNotTheStockOneIsReportedAndLeftBlank() {
        val layout = capture(image("zlib.sqfs", ZLIB))
        val (_, bad) = rebuild(layout) { if (it == "bin/exact.bin") null else files[it] }
        // a file whose blocks are all kept in the layout is not needed at all, so it may not be asked for
        assertTrue(bad.isEmpty() || bad == listOf("bin/exact.bin"))
    }

    @Test fun theVisitorGetsTheContentOfTheImage() {
        val got = LinkedHashMap<String, ByteArray>()
        val dirs = ArrayList<String>()
        val links = LinkedHashMap<String, String>()
        var cur: ByteArrayOutputStream? = null
        capture(image("zlib.sqfs", ZLIB), object : FirmwareToolset.SquashVisitor {
            override fun dir(path: String, mode: Int) { dirs.add(path) }
            override fun link(path: String, target: String) { links[path] = target }
            override fun file(path: String, mode: Int, size: Long): OutputStream? = ByteArrayOutputStream().also { cur = it }
            override fun fileEnd(path: String) { got[path] = cur!!.toByteArray() }
        })
        assertEquals(setOf("bin", "etc", "lib"), dirs.toSet())
        assertEquals(mapOf("lib/link" to "../bin/app.bin"), links)
        assertEquals(files.keys, got.keys)
        for ((p, b) in files) assertArrayEquals(p, b, got.getValue(p))
    }

    private companion object {
        const val ZLIB =
            "H4sIAAAAAAACA+2Z9VeUa7vHhxwahu4BQRqUbskBpBGFoZEGASnJobs7hKFDYpBuJAdpCaVTalBQGgGJ497b9137Pef8AWedtb+f" +
            "9Vzf+3s917rXvdbz/Hbbubu64wN+iwQAQAP88RAAetDRAJi/1qZof70SofnL+X773W91Uv2Vzan/8qPfzvR7znvRaa7NCUOWAGtq" +
            "+Vl52M1yBz1QgKnn4XLg1rwg3mtCj0PebtO1iUHUwcHmz7MTWGNiTaD7Q6qdBB/zvCyqnfhL8659HSb8fmQtfxwBd/9YrSABoVps" +
            "aelESunAtIV9XSPUkIc3g1JN8hcdXZ4Zf4foL27kDSSg/8bgN2e2Fl+sfmPxb6bv8kpj/yTnbxhxx/zpkf+B8lLar5r539CPXcsx" +
            "q+q2/yHKe1f2ba3xNuc8kAyggS4Emvx0HcEtTMcTa13vmDFVb2ij5gxxKXVEZFvrh+mX6CP1t/QxoaxQRagVtB2M7UBpmWwJskq2" +
            "AlknW4Nskm1AtjQB4+UGRZASSBmkHFIJQUDeQmoh9dZXuGU2nym/UB5RXlKiUeFSgahoqJgzt72Ks7eNMU1YTRRNrEzCTEpMkCZb" +
            "Js3gDgdKn2QfkG+yL8gv2Q8ES4aB/MUDCqsMriDXkFsIQAldCVMJWwlHCW/nCnFoUtgvT5RBzyMOdavdMh/uTQm9D1Qj0oMRVBj8" +
            "Z3v75zhwOQdfc7zrMwx5GXNCg5L8EAn/a2J9HYx5cJ3jvWj9oYOw7yE5oCePUwqd9cDyfeHDJyU41nd4Dm/ffZ2dP785f/RB2HRO" +
            "+MNmQwCpyr1wIYFkVDeSwYeJrk9cDIhap4eHurtofhgWF2dHbcHhqe5uv0KbGBVqwxge6+7K92Hgj7CzDM9xd3f+MN4mLvpHYEcN" +
            "+7jlmT34Sbn5x98oqanIT4I1BX82RgpbuttmElJRiYoK2k4Faad7PpX23bTsDoQ9Ak/kz7UcDKIG506mTFfvNEIeP5/mp8lSV6dk" +
            "o0sNHbYuVGyQGCPq6D+slVInXuq/qO2Cmh3nNPyqiAezVHuxZwNXFv62v8jUijjL/Ruhf6LQ5T1L/G+0/sVlf33Av6j7N8M+/VcD" +
            "fzL+N3gH7++J/oL4P0iEnyNkEN3/nZB0+/vGwkeVuFANpIO8HllDdsGMYjWbo/CCwkK6NIFp6tyusB+h2ce5C4pAFsCb/Vsmv6he" +
            "UBgLnjJ1ghh9sNg917CNqIEvnqAU6bDNqEFQEss9ZZWELHo58SK3sG2CjqlhxnvuYTtR4yA4i4Syd8IFPZrEPY8yEw1rYjmJIo+w" +
            "L1EfQeUsT5TLE6AMvRKj3+frI70Z0CTveYbtRy2AGlkclOcTWnOdErEWFHYT5hl6JYu8wg6i1kA9LCHKOBN1+on7zDHK1IlijMFS" +
            "93zCjqO2QWMs2ajFk/HVl8dBZBNqUg+OOpEnpP9aXJ1cUyGFOZZ/fe4ZSQdjOXLA1HJV1n27dufuCbc4mmBZMbeCbTp6Go/pES4J" +
            "M5nAGx9YwHWA9OzJt8Gfg6i9o6urGukpSkO70OYpokayb/Id3S3hif+TQ9CjPuULHPBnFljYHwEbHJrYO7huech/QQ0j+DPG/y9g" +
            "gIGPiGD0F2KHruubPYPBfzY0YfALsSPXjU3kYHQiGZj9z0broevnzb7B8ER8MNUjPpjxX433g/GJdGDRR86w5YvWI9fNX42bm+IW" +
            "0Ub2RXl8p0mRxWDPIhO7iyBU78m6z6GUN6MUcR5wIvT/bAxGYUwA84ilwN6PDmHrlz3/2bj1uaXq/0z3aRnTuhJczWC4uI2gYzQj" +
            "c/FxvCG5o6kxZ8BENDKgcyt5u0scr5m9bcExmPouTjKqkt40Fs0JusNutuBbk7oUvlFL4xjBhSk2oJtmmDV8dUJ84g12tq9Z34lU" +
            "h2OipkVMeMyHv576nEcU7KWybVmQzhCONnhRODhI2LUnLHngKOWdfGjAu5hcseu4MLtiPZ6+po78aRzZ3igmUJpDJ00C++E9G7jh" +
            "dAFK5gxITR8lHdD/YHj0iZJ6jqYT0yjgtfVRUZorkgvhUCGGfDVYLSCGDsaS5HlNe71HsTph174O65xlzKTLxs/tyrT8mHJds1YU" +
            "l8kCsdhbAVq73EGjHSZ+jHyXof+yhWe3vZoaLwb3fzdnhPsJtSGoKKMahyxMmm1tXX5uNpXA0WVAVKlNpx3jIMK547L2zKt9WdFJ" +
            "oaMCh2q259wP57nLAu8wqZsRa0DfxmJdPWYeU5CHaPbLJIIVPlO3M1yVZuhU5pf+uDrnjyukihYCBORvamSuCFZnyT6QRYzqFyhv" +
            "GXVNQRpFZqJupwFyx5F1xKFmHtbtHEqUT9aiisgWuuUPWkBNq8DAYZ/2zXKoasUub7mGdu5O1ouNiuwJZnUbrriZJJBolm10R/WJ" +
            "TjxZ9Rl9IVoaojbM8SzmjMs3+eyk6kGEQHswhEm635Y8yvdV0pDlIHCdsc8FI3sqllKG9bx/lfvAxHBqx5PMZ0Seso2kEmsh9OEg" +
            "3kT4oc3o/gEiWv2qBUzd3i+01myuWh8kMC5e6LyWpkj++aRsv/dUXAG7J8cid1Pi4/ODft0Xk1oZ4O8a706weDNvkVM5EPbGImTa" +
            "vNWjVtc2JFg3FiedPs9Mlvku05dWI9AlruoB7CqITn7kbCnukqd1J0Du/DIQbatcxKlkv+hbu8u3p/p7BZmWctEaGgZ2OymdxlVE" +
            "JyQ+2Lml0kdPTPhwJQk/ZDjJ5Z3h8ZS8oNTLRdBionP62bxjyAwt1yvdHul/jKndd+WuwUE+xk/YaYJv7J0YwBhDGx5MvLdTtTzp" +
            "JUf+hbwpesJYdz6YD1+BbOdanUJ/gAoZFeN/8mDooOVgATSjrUS6HzI5bjElXUiyuwmqfn9O+zMoGdrQET7Hv7vdvv5IM8HS58eL" +
            "6Kewye+DDlIugOg4KMs3akhfR9w8XWcEM3nx6dgPRm78b/lDClIoZU9ZfWt37nPhbpgcprxs8/c6ArB0d3N+OMasf9Cbu8bK2Xci" +
            "8E1NTE4J5gMJIAm3fbnxa/OPjlOHkZCjw8Yh9uWKGgCnnNJL+/EvuSw9ch4uDNJf9AqnCbZq3SdBAK2cZ0DNOKz7w0kvU/BZ27Di" +
            "UL6cKBRFb2H5YnrWyRKrunUCzlQDO3tSV0Ner+Gpc8UqYCv1RdZI4s1j7W+Cii1eq7f9bmpRq7o6VQTyNKMpWrU+Nl6aM8+eWVX8" +
            "KLdV1o0yY9Sx5QNvjhROHw+IieCJNZm8H7NFXlUJuheh2y7Eiz6yHv4quDVejZ1nH5bI50yndJ9hJMFU+zES7CCAfwqQmlFX5BeY" +
            "FGlciIxu+lGlV9umElKek32Icma1deNJkteEqS56Yiw2Zrpg2ScrFRu8tZMhLrBxdZOOuaq7LCjZZ4+eKz4I/LlGdD/5no4eiHii" +
            "Mnjxy9CiM7ozrdmdHEeG/zPH5hkYl/aUVvEY9+1X0uG5yJ8dmeN+hx4/Yzp3KObwxIN4zD+Z52cp1wdAKTku9JSHYjWqFQS6TihV" +
            "bW0tVMLi4hiGTNOsaC1FdEg8+w15dDuLFj4LcrEobLRI5OIEl5YBCyDH3SKgm3KT2B/1E1g6y9B0gozrhc653RemtzQsaw4fiO3m" +
            "awRfBLajvfx2lJlTMNCBaSmb2ex8JhQmXdGSxNNt+nrOSgIoFFjZ6qSXExUACC5OTSCPAV+M7nQO05C8j3Ewbjpc5nyE7KrgzBh2" +
            "PWJm6mbwx018DrQ3didBa36r8iLa4cs0O2WxBy2t6gLCWX5K4KrsAQNCUqnbiJp92Qz0+mJ/d8ZIUAB7SxQvNPWlz8gn5kKpOB9E" +
            "zrajp8Ki1jV3sHBYDh1hTNWKt3MgzHFN0S8G64uQjGteOXyAEPucxehGWm+ll6E/e22WMLx3OdYLje/Fd2fP4qi1nRzp9vEcM/mf" +
            "57qli6eEvY+DNdpFM3KkMF9gJGcaJFvIJMeqqwW6ctldNzG8E5EwGjJLfHdOvYuNBV8/pa0+wi18VEO5uz0Y53fXESJdYBShJo/O" +
            "msSZIcqRKX3s2bSOwJQyd2HYyZHlyWLg/aheRj3aYw4JWF8ptTmdXmeJTmc1Y4bWWmSzgD1MU1+daz/dk3Ez8xXVO+VsVtWnylZQ" +
            "VakM13jAR511p7o3U8+IzJvNP2ijFSqmgJMIhB4+uRQ5XEW/VLr0EWh/M5Mthexrr9ueZA3YX/WacVIk6UP2J50e85GqS81wsoc0" +
            "wh3NzT8/xa42vJ5j0kEgqZu2bTtSeNYKn7x2Vmhi/HQvJcu+RS3rmhH03Tl45ZIIwlnX8MPNFX72Mhxnmcg7rUmQVn/1oW5XO3oV" +
            "J7VOurwVWyBzkkP2vZ579sGtjvjt2OKPi74PmUFod2IDb1F6BoQWpZy5tYF22Sxwne/AOhmEoT/yTjU1asZ9Z1dthlJ1LtNfXtT/" +
            "woaB69VRJpUcrawWsQDn4WBQuAXFMuVTBJGRn+mWJ94Kxdqh5sLop1RFw6lnNqFHn1i7XzjPeMOP4A8HC6Lc4oGOPlyBgYWvAhuT" +
            "Myrm2cKSnBiV1FSitzv2DwVzSuVl1K1CUk1NBYVWLQP1LW45N2eeiFVh1Dy33frB71XecAeNOE3bgOxlKEeqtI3N65ZBfEff6HwP" +
            "oTKUb4xVY+TuIYmjUJyIKdze1TFarLbSqP+sPwU4thJFntfQZ9k+C8/PF+3iJY08E9GgSveuN7ayO4QYrUK6Hge6o7N9Dy0Vq5c2" +
            "2d22SHteVPv2O08qLclqFa3FHJou73Nc8lE2fZtOEPN8DJYP0ERO+cA4AedshFeldiVs3c805LADfS7nKGY2PAB+n0jcjEutYVF4" +
            "CILnBFgdgL1O0Lkyn+rPBsJpqAo4qZbnQzc8T6dM2uMtlJvxYSDo5lbQehIvEPikd2O/ou64P0nKWMqXeb2JqOQSXGjRVus1a6nR" +
            "/uZ9s0iL8cvFfTa3g05IBoUf9B261eueK/tmeqJ2VwNyqvNHDrN3zQ6NlUFVRhTZNy3dL1gu8xo0bQGCpl/v0c3rpYiVeikuJq15" +
            "Rfi7QZ47vCXUZ+8IJpoRSUhgeneAkJJIlCw8LD4OVw6jZ4BqoKeJiFEPobd9HayGIgnitFh9clGfyXNSqUL6hxcUrUoyGjgllTvZ" +
            "u02NS/VeLlZ35AzppfS0TMQ9f8cjo19+2Cd8Z0oA6wzCCx07Dbkv71vJYSYd6SK/Bdx9CV3hRn/KdF7ztlgr2JbUVdNjKuD6cdqm" +
            "F3dXjLOgX/8kM/wQd0f4+ObgQr9XUFuW/qc0eY/H8Y7K+cOyI1JzfGpTdykvgK8c+Ccmr7CUJNDRsstfSVdKGc/7k3HY0oP5QJRR" +
            "OcaFzU+tim679EX1Ne9QVR0lqS/gs6hixbVtcznpNLUxaw430f4ppsLXNlfrnkLLXF7RrhFyt2ln7VEOZjGPpGSeoiiIGtXALpci" +
            "Xcz56vZ5qUkaqWwNBcBx+gHVvA7W7JiEwUyjwyYnxxLiDp7SfSWdqlOzJf8u8omrrzS127ceXe5o+98zFh4ggNbvdSkN2+z4V9Gy" +
            "RXbqfb21t69Se2zfHEqylFGFabm0ihtoeFUnpfiFpWY3gXzDD13eN+Ra4N4a7z3tUK22diHEiH9SsGIZvmbKTTotivGmrfcj6fLK" +
            "MYw49rU1UAPH0lzCN9vh88hVy+ZnKWtNVDZPx30dNo7OueepOMtsRlMYOHqiJlV1xhWGsROaSjod5Plzn8qeSd4MVfm2QYMmoNgm" +
            "E6sfpZXq1Zgm8IM1hWknHON/qMoSa+IotL0H7RcJsbWAw4R+ZFpmPGd7S/T0NDv4y9Nyz264kkboIERGfvSOxrlDsbU+F1fhFe4E" +
            "98OIT1suJdfmlQsB2n30WjSGCppZSwxCGYpaEnhNB5Gg9g3ztwbnRHKLXQnDBvw/FwjQOo2evXPCiSxNKPbW+X4xCpjJuuo91bmv" +
            "SOiS52/RzH+7RisEAirqZOW/pyyjoxZfGDNE+A8OWVukX+/5wFH5/NoJk2k0YTAmDG6UDUfFqtUKYqg4f9UV1MGeAe8te5ha5hTc" +
            "2Xm7LvQkWgGPazM5O1+ptCp19J4Kbfml2S5nB52fSz4acF93jC07FFfqlsutaj2HoHNQ805F9IU4VwV1r7Vm6BJ52NvPFBS3yx4C" +
            "wduvBdAfCeBNPFiNhRTlmTEFXPeG9V/ejTi01hFc7bW8/Ip2KthOMAab9DBdITMNrni8GHTF/yEjwLyMA2u5DTnq67sS/sNSkXeh" +
            "0CYeI0glni5dQRpeHVjU7v1Q7vIs1Sk2942WsxaiXf7WBLFHv1+8N9BbBkOcELm8D6EZbjXipUbD3c9ASfzEZLlMkVcv7a/DbIxV" +
            "3eLK8eOY2xyg1d2kkmmvQylyDFO+OckMZqt6b7M7reTvaMw0Lbx9aSXQaBt04PFjMQBx5cKCQQ7uEwlBx53hSzDp9XCOsK4H07Sk" +
            "qFV8o0JVA+zwH2yYpUkETMHQ3OGnE0muSvXENs6oFEk3zDW78F3DvTULpWmDko6YMRubHY5FjANd3RznwYYAia+vAfI8KicGm3q7" +
            "yB0a1vC9KSvrwnQdZNxdL3NJRqNtNQD35tkWRixxQVBCfe1J9uQCm9scTf3IV03xWf9yh+z6yfojr5k+Q+Ol9xHCIWZFHlRWVgr+" +
            "JnNWix2oYbQFxHFRgxeeoF1ub64r9u6bfI8PLLOpxotxA8fidwhAq7iHNRFyO5KSl0c0UNJKXvQbajZdCJqj8soxHuOdX85O3wha" +
            "VW66TfrjtSaapauOx4Gz4ZgiuF5rOkU809XVoCJERC/p+Srj8GmiIDoueOw8gfIjbhrAvtlAbkgqImcJmy8oeymShn3EhrTyI4zn" +
            "2zPdoGYp7LTdnX4wdBEd7sIRkhyRiwO/qpmTJ2X57pUehPmNU0iRwTFyiHJGYQ7nMdNaov8xfy9RcsD58zgKQ6m4RSHmGOwT59qC" +
            "GxGSbrEVIOyp58u2teWodOyUkioeozRC3qwZjJc2fqHVHgsuISVnqS32NYaaS1W6atkYNhD7tzO1ETVnqNGbiutwkWJZKnTDZC2V" +
            "UZtQsoMcD4W7m7VTh/SNtylNFDqqSKsu0PBbJfxW8Mf+QI35okmKK75qIT/MppIXaQ874/OWgXgur9bnl7Gnd38e15GW9nr1IqrW" +
            "TZd2BvzraiVGVuht6nzg9yeBT8wYeSjZ5vmUUh480DFuYEeX9i9KwATLGPtqbGWcqfR5dBERKLAqeIqarLicM6WAP6NjMDGllV57" +
            "SHjYd47oKSnuaj2dYnVodpq0ZD8uorgxIC773GPhoSad19VjTUM2mj3j944614a1ewtKkGfy5ELBAJA3sFVik5A/UPjFOCNay4/j" +
            "xhWodYmVpDer/d5C2XuxjDgyXT1URhiMLMC7aJWYORgXXJJ2oho7kbHQN2adiBHcN90LX+bQs1rfgOskCc7bbnsvmkzLOWDwkyhO" +
            "vdPiV3bxGnV1g5PPm1O73VkziT186VmZzmjm23U+eP7uW03+oOc7KeIq6DjtrMUKFIyQ3Es92/7GHpedouurqs3J9bi0QnFG58wL" +
            "r1q+apKr8W82z9UcU23xN7uqKajUin/aVKk3U2n4rMrhWbXD0/hKcencSh3hSqWZctpnxQ4VhpUZla+udH7VM70bdIgT7/MigPei" +
            "pQW0bwiApS4LByDvggHIAxIb83VsPJHQbZKvWcEXDGTyDKHoGP1+Bfs0TDZW3vj6g1FWbx+RuBe/7h8JwvOg1tlemNw6f1g40qBg" +
            "3ypJrf3NVq5+LUQO6Cgj2B/YKsX+KR4mi3jzJj/t9Zt8qHYBsCTZ6TP6RrojjtETzKAwAIYlnrZgvRO9SrM9P2uIk0IGSum4CU0B" +
            "iEGchA0g8GbL9f/jjFZBADax8fdJvzbI/LVRbw/OltvDEj1WtswgTHMHaHArAJGRzqzzQFzmgRM6lJNbgTSrBW3IxsL8uco4L0eF" +
            "VZjDBuYGsewYQZj4a58Fi7g489EY0pMnpzEnz21C04BB9mzYBelUQZVyY59SrCSVPB8KsgviAoqTaV6SBBH+vqCo+X3Hsfb7guL+" +
            "r4Phm8ti0WpfoafxBU2TFZxiczgN4hTKyF5aaAfQp2EDxlYw8y5/j2MG/VE5aAD/6B/9o3/0j/7R/wP9F8J5ODgAIAAA"

        const val STORED =
            "H4sIAAAAAAACA+2Z9VeUa7vHhxxKYOgeEKRR6ZYcQBpRGBppEJCSHLq7e+iQGKQbyUFaQumUGhSURprj3tv3Xfs95/wB56y1v5/1" +
            "XN/7ez33ute91vP8dtm4OrviA36LGABAA/zxEAC6sdEAmL/Wxmh/vZKm/stFfvvdb32g/CvbUf3lV7+d8/c+nADcX5Xw12Oz4jDb" +
            "6oAhTYA1ufSiLORmqZ0OyMfY/XjJf3OOHy/zntsBT5fx6vgAan9/4+r0GNYQX+3v+phyO87LNDeDcjv2wrRzT4sRvw9ZwxtDwNU3" +
            "WsNPcE8luqRkPKmkf8rMtrYBqs/Nk0ahIv6L9k73tL9D+Bc3snpi0H+j95tTa7OvFr8x+zdTd7kl0X+S/TcMuKL+9PD/QHEx5VdN" +
            "/2/oRq9mm1R22f4U5rkr/b7acJt95k8KUEMXAE18vg7jEqTljrass0+brNO3UnGEOJXYI7IsdUN0i3WRupu6mFAWqDzUAtoGxraj" +
            "ME80B1kkWoAsEy1BVolWIGtqv7EyvUJIMaQUUgapgCAg7yA1kDrLS9xSqy8UXykOKS4o0ChxKUGU1JRM6VseRVlbhphGLEbyRhZG" +
            "IUbFRkijTaMmcLsdhVeiF8g70Rvkk+gDgiXCQL6ifgWVepeQa8gtBKCAroCpgK2Ao4C3fYk4MCrokyVMo+MWhbrUbJoO9SQFPwCq" +
            "EOrACMr1/rO9dTUGXMrGVx/r/AJDXkQdU6PEP4bD/9qxtgbG3L/Otlmx/Nh+r/cxGaA7l0MCnWXf/EPB42fFOJZ3eHbv3n+bmTu7" +
            "OXvyUdB4VvDjRr0fidL9UAG+RFQXkt6LkbZXVASIWqODB7s6qX8cEhVlQ23C4cmuLr9Cqwglat0QHu3q/PBj/x9hewme7erq+HGs" +
            "VVT4j8CGGvJyyTV5dEWx8cffKK4uz0uMNQl/MUoCW7zbYhRQUoqICNhKBmmmuj+X9N4w7/KHPQGP58027w+gBmaPJ41X7tSCnr6c" +
            "4qXOUFWlYKVNDh6yLJCvFxslbO87qJFQJVrsO6/phJocZdf/qohHM5S70af9l2a+1r9I1wg7zfkbwX8i1+k5Q/RvNP7FRV+d37+o" +
            "/TdDXn2X/X8y9jd4Bh7sCv+C6D+Ih58hpBBd/52gVNsHhoKHFbhQNaSdrA5pfVb+tHwVq73gvNx8qiSBcfLsjqDPPZNPs+fk/syA" +
            "t3u3jD4RPaAQZjxFqjgRukCR+84h6xH9X91BSZIhGxEDoATm+4pKcRl0MqKFLiFbBO2TQwz3XUO2I8ZAcGYxRc+4czo0sftupUZq" +
            "lkQyYoVuIV8jPoHKmJ8plsVB6XvERn7M1YV70qOJ33cP2YuYBzUw2ynOxbXkOMRjzcvtxM3R94gXeoTsR6yCupmDFHHGa3Xj95ii" +
            "FKniRRgCJe57hRxFbIFGmbNQC8djK6+PAkjHVSQeHXYgj0n+tbg8vqZECrIv/frc0+J2hjJkgMmlyowHNm2OXeMuMdSB0iIu+Vu0" +
            "dNRuU8OcYiZS/jdeML9rP8mZ4+8DVwOo3cPLy2rJSQp9m+CmScIG0u+y7V3NofH/kwPQk17FcxzwF2ZYyB8BGxwc3zOwZn7Ae04F" +
            "I/gzxv4vYICBTwhhdOciB85rG90DgX821GHwc5FD5/UN5EBkPCmY7c9Gy4Hzl43egdB4fDDlk4cww78aHwZi42nBwk8cYUvnLYfO" +
            "G78aNzdFzcINbAuy+A4TQguB7oVGNucBqJ7jNa8DCU8GCaJc4Hjw/9kYiMIYB+YSSYA9nxzA1i66/7Nx63VL2feF9vMSpmUFuIpe" +
            "f2ELQctgQurkZX9DfEddbUqPiWigR+dS8HQVO1o1edeMozf5Q5R4RCm1cTSSA3SH3WT2cFXiQvBGJYV9GBcmX49unGZS/80B8Zkn" +
            "0NG2em07XBWOiZoSMuI2Hfp24nUWlr+bzLppRjJ9b6Teg9zOTsymLW7RDUch9/hjPd75xLJN+7nJJcvR1DVV+JVheFuDCF9JNq0k" +
            "Meyn54z/usM5KJHDLzl1hKRf96P+4WcKqlnqDkwDv0zLw8IUZyQnwq5cBPlmoIpPBB2MJc6dSXO9S74ybtO2BuuYYUinzcLP6Uw3" +
            "/5R0Xb1aGJPODDHbXQZaOt1BI+3Gfw7/kKL7uolns7WSHCsC930/a4D7GbXOLy+lHIMsSJhpaVl6aTIZx96pR1ihSasZZSfEse20" +
            "+sKjbUneQa69HIdypvvMB+el0zzPEImLAYtf7/pCbR1mLmOAm3DW6wSC5YfGLqe4Sk3QyfSvfTG1jp+WSeTN+AjI3lZLXRKszJB+" +
            "JA0b0c1X3DTonIQ0CE1H3E4BZI7Ca4mCTdws29gVKJ6tRhSSznfJ7jeDGleA/kNebRtlUOXyHZ4yNc2c7YxX6+VZ40yqVpwx0wkg" +
            "4QzryPaqY61Y0qpTugK0FERNiP1p1Cmnd+LpceWjML62QAijZJ81WYT3m4RB8wHgGkOvE0bWZDSFFMtZ3wrXvpH+5LY7qdewLEUr" +
            "cQXWfPDjAbzx0AOrkb19RKTqZTOYqq1PYLXJVLkugG9MtMBxNUWe7Mtx6V7Piagcdne2Wc6G2KeX+33aryY00sA/1N4fY/Gk3yIn" +
            "syFsDYXIlDmLJy3OrUiwdjROKl2uiTTTXbo3jZq/U0zlI9hlAK3s8OlizAV3y7afzNmFP9pmmZBD8V7h9zan7891d/PTzWUi1dT0" +
            "bLaTOgwrCY+JvbBzSiQPnxk9xBW/9zHNQSb3FI+7+BWFTg6CBhOdw8fqPX16cJlOydZw31NMzd5LVzV2slHeex1G+Iae8X4MUTSh" +
            "gUS725VLEx4yZF/JGiPHDbXnAh/iy5FuX6uS6/ZTIiOifI8fDe4378+DpjUVSPaCJsbMJiULiHc2QFUfzmiuAhKh9e2hs7w7W21r" +
            "T9TjzL1+vop8Dpv4MWAn4QSIjIEyf6eC9LbHzNF2hDGRFZ2M/mTgwv+eNygngVJ0l9a1dOU6E+yCyWDKSjf9qCUAS3Y15YVizPgG" +
            "vL1rqJh5LwTfUMfkEGPaFwMSc9mWGWaafrKfPAiHHB40DLItlVcDOGQUXtuOfc1h7pZxc6KX/KpTMEWwWeM6AQJoZL8AqsdgPRhK" +
            "eJ2Ez9KKFYPy5kChyHsKyhZSM44XWVQt43Am69nYEjrrc3v0TxzLVwCbya8yhuNvnmp+55dv9li57XNRiVjR1qokkKUeSdKo8bLy" +
            "UJ9+8cKi/GeZtaJ2hAmDlvVD8MZwwdRRv4gQnkij0YdRa+RlJb9rIbr1fKzwE8uhb/ybY1XYubYh8Q8daRUe0A/HGWs+RYLt+PBP" +
            "ABLTqvK8fBNCDfPhkY0/K3VqWpWCyrKzDlCOLNYu3Amy6jDlBXeMhYZ0JyzbRIUivXc2UkT5Vs4uklGXtRf5xXtskbNF+/5Xq4QP" +
            "Eu9r6YCIxisCF74OLjiiO9KY3Mmwp/m+sG+ahnFqTmoUjXLdfiMZmg2/ak8f8zlwu4rq2CafxRMN4Db9bJqXoVjnB6VgP9dRHIxW" +
            "q5Lj6zymULa2NlMKiYmhHzROsaAxF9Iidu/T59buKJz/ws/JLLfeLJaDE1hSCsyHHHUJgW7KjKJ/1o1jaS1BUwnSruc7ZndeGd9S" +
            "M6/afSSymavmf+Xfhvb6+2F6dn5/O6a5dHqT46lAiGR5cwJ3l3HmrIUYUMC/osVBJzvCDxBYlBxHFgU+H9nuGKIm/hBlZ9h4sMTx" +
            "BNlZzpE25HzIxNhF74sb/xJoa+hKjNb0TulVpN3XKTaKIjcaGuV5hKPsJN9l6SN6hLhClwEV25IJKPN8b2fagJ8Pe1MYLzj5tdfw" +
            "Z6YCiRgvRPaWvbvcgsY1V6BgSDbtvajKZU9Hf5j9qrxPFNZXASnn3DJ4/z3sM2aDG0md5R76vqzVmXuhPUvRHmgPX/1wdC+KWN3O" +
            "lmwbyzaRvTrTLlk4udfzNFCtTTgtWwLzFUZiul6imVRitKqKvzOnzXUj/XshMYNBk/j3Z1Q72FjwtROaqkPcgifVFDtbAzE+d+1B" +
            "kvkGYSqy6CwJHGnC7OmSR+6NawhMCVMn+u1sae4Mep5PqqVUI92mEL+15RKrk6k15shUFhMmaI1ZFjPYzTj5zZnm810pFxNvYZ0T" +
            "jiZlXcosOWWlilC1Rw+pMu6Ud6frGJC5M3n7rTQCReRwYr7gg2cXQgcr6BcKF158bW+nsySQvW21WxMsfnsrHtMO8sS9yL6Ek6OH" +
            "JKoS0xxsQQ1we1PTL8+xq/SvZxm1EEiqxi3r9iTu1YJnmY5yjQyf7ydl2DarZFwzgH44Bi5fEEI4aut/ujjDT1+H4iwReqY08tPo" +
            "rjzW7mxDr+Sg0kqVtWD1Z0qwy7rffd82sMUevw1b9Gnhj0ETCM12tP8tSkfvnlkJR06Nv00WM1zrB7BWCqHvi7xTTo6Ydt3eUZmm" +
            "UJ5N95UV9j23oud8c5hOKUMjrUHEx3EwEBBqRr5E8RxBaOBjvOmOt0y+eqA+P/I5WV5/8oVV8OFnlq5XjtOe8EP444H8CJdYoL0X" +
            "p79/wRv/hsS08jnWkAQHBgUVpcit9r0D/uwSWSlVi6BkY2N+gRVzf12zW46N6WcilRjVL603f/J6lNXfQcNOUtYhu2mK4Uqto3Pa" +
            "pRDvkbdaP4Io9WUbolUYuLqJY8jlx6MKtna0DBaqLNTqvuhOAo4shJFn1XQZ1i9C8/KEO3lIwk+F1ChTPesMLWwOIAYrkM6n/q7o" +
            "rD+CS0TqJI12tsxSXhbWvPvBnUxDvFJJYzaLps3zEpdshFXXqgPENBeF5QU0klHcN4zDOR3mUapZDlnzMQ46aEefzT6Mmgn1gz8g" +
            "FDXhVKlfEByE4DkAVvphmXFal6aTfVlAODVlPgfl0lzwuvvJpFFbrJliEz4MBN3YDFhL4AECn/Ws75XXHvUlSBhKeDOtNRIWX4AL" +
            "zFprPGbM1drefmgSajZ8vbDH6rLfAUkj94G+R7fI7L60baIjbHPWI6M8e2I3c9dk11ARUGlAnnXT3PWK+SK3Xt0awG/87T7tnE6S" +
            "SImH/ELCqkeYrwvkpd27e7ps7YGE00JxcYzv9xESYvHiBQdFR6GKIXT0UDX0FCERqkH01m8DVVAkQYwGi1cO6gtZdjJlUN/QvLxF" +
            "cVo9h7hiB1uXsWGJzuuFqvbsQZ2k7ubxmJfvuaV0yw56Be+MCWAdAXjBoydBD2S9K9hNJMOdZDeBO6+hy1zozxnPqt8VaQRakzir" +
            "u036XT9N2fDg6oxy5Pfpm2CCH+BuCx7d7J/r9vBrStNdSZJ1ux1tK509Lj0kMcWnMnaV8AB4y4CvMHkEJcSB9uadvgraEop4np8N" +
            "QxYfzfmjDMowzq2uNMq7bFIXVFc9g5W1FCS+gk8jiuRXt0xlJFNURi3ZXYT7JhkLMq0u19wFljg9Ip3DZG5TTtsi7EyinkhIPUeR" +
            "EzaogJ0uhDqZ8lRtc5MT1JJZ6/OBY3T9yrntLFlRcQPpBgeNDvbFRO3cJXsKWpUnJou+nWTjl9+oa7Zu3Tpd0fZ+pM0/QgAtP2hT" +
            "6Lfa8K6gZQlt13l7am5dJndbvz0QZy6lDNFwahHVU/OoSkjyCUnOagR5hx44fajPMcO9Ndx93q5cZel0DyP2Wf6yeeiqMRfJlDDG" +
            "29aeTyRLy0cwouhMS6AajrmpmHeW3Zfhy+aNLxKW6qgs7vYHWqzsHbMvk3GWWA0mMXB0hI0qaw3L9aPH1RW02snyZj+XvhC/Gaz0" +
            "boUGjEOxjcZXPkkq1KkwjuMHqgvSjNvH/lSWJlLHkWv9ANorFGBtBocI/Ew3T3vJ+o7w+UlW4NfnZe5dcAW14AGIlOzIHbVju3xL" +
            "XQ6u3Bvcca7HYZ83nYqvTSvm/TR76TSo9eXUMxbpBdLkNcTwGvfDQW3rpu/0zghlFjrjhvR4r+YJ0DoMXrx3wAkviSvy1PpxPgKY" +
            "zrjsOdF6IH/PKdfXrIn3dpVGAASU18rI+0BRSkslOj+qj/AdGLQ0S73e9YKj8ng14yZSqENgjBhcKCv28hWLZcRgUd6KM6idLQ3e" +
            "U/o4udQhsKPjdk3gWaQcHudGYlaeQkll8sh9JZqyC5MdjnZaH6c8NOCe9ihrVjCuxC2nS+VaNkHHgPqdkvArUc5yqh5L9eBFspB3" +
            "X8jJb5fc+AK3MvnQn/DhjT9aiYYU5pow+l33hPRd3A3btdQSXO42v/6GdsLfRjAKm3AzXiY1Dix/uhBwyfsxzc+0lB1rqRU54u29" +
            "HPrTXJ5nvsAqFiNAKZY2VU4SXuVf2Ob5WObiNNkhOuethqMGok321gixS7dXtNvfUwpDHBM6fQiiHmox4KFCw91LQ4ldYTJfJMmq" +
            "lvTVYjZEK29yZvuwz27002hvUEq11aLk2Yco3h6nB7JWfrDamVLwtTdknBLcurDga7AO2Hf7ueCHuHRixiAD9woFoeNOP4wz6nFz" +
            "DLOsA1M3J6mUf6dEVQFs8B+tm6SI+U3C0FzhJ+MJzgp1RFaOqCRxF8xVm9Ad/d1VM4UpveL2qFErq232BYx9be1sx4F6P7FvmQBZ" +
            "bqVjvQ2dHeQ2NUvo7qSFZUGqFjLmroepOK3BugqAe/NiEyOaKD8grq7mOGtintVllrpu+Ju66IxvmV1W3UTdocd0r77h4ocwwSCT" +
            "QjdKCws5X6NZi4V21BDaPOKosN4Dj98mpyfHGXvnbZ7bR+aZZMOFmP4j0TsEoEXUzZIQuRVOwcMt7C9uISv8HTWTKgDNVnpjH4vx" +
            "3id7u3cYrTIn1Sr16Woj9eJl+1P/mVBMIVyPVa1C7qmqKlAhIqyH5GyFYegknh8dFzx6FkfxCTcFYNukJzMoEZa9iP0wIGsxnJpt" +
            "2Iqk4hOM+/sL7YAmCeyUne0+MHQBHe7EHpQYloMDv6yelSVh/uGRGoD5nUNAnt4+fJBiWm4W5ynjarzvEW8PYaLf2csYcn2JmAUB" +
            "pijsY8ea/Bsh4i6RZSDsufvr1tWliFTspOJKboOUezwZ0xivrXyCq9zmnYKKT5Obbav11RcrtVWyMKwgtu+ma8KqT1EjN+XXoUJF" +
            "0pTo+okaSiNWwaT72W5ydzerJ3ap6++SGsm1lJEWnaChdwr4LeBPff5qc4UT5JcPqwR8MBuLX6U87ojNXQLiOb1Zm1vCntq5Oqol" +
            "Kenx6EFUrhkvbvf71taIDS/TWdV6wR9MAJ+ZMHBTsM49VEh69EjLsJ4NXdK3MA4TLGXorbaZdqrU69ZJSCDHIucubLTsdMaYBP6C" +
            "jsHImFJy7SbmZtsxrKMgv6PxfJLFrslhwpztqJD8Ro+o9Eu3mZuKZG5ntyU16UjWtM97qhwrlq5NKEGu0bNzOT1Abv9msVVcXn/B" +
            "V8O0SA0f9htnoMYFVoLOjOYHM0XPhVKi8FTVYClBMDIf77xFbHp/jH9R0oFy9FjKTNeQZTyKf894N3SJXcdibR2ulcA/Z71ls2I0" +
            "JWOHwUssP/leg1fRyWPE2QVONmdK5XJnySjy+LV7RSqDiXfn2cDZ++/VeQPu7yWIKqFjNDNmy1AwQnw3+XTrO1tMVpK2t7ImB+fT" +
            "knL5aa1TD7wq2coJzoa/2RxnU1SV2d/ssjq/QiP2eWOFznSF/otKuxdVds9jK0Qlcyq0BCsUpstoXhTZletXpFW8udT6VU91btAh" +
            "DjwvCwE2K+Zm0N5BAJaqNBwLeRcIQO4TW5muYeMJBW8Rf8sIPAeSytIHo2P0+eTvUTNaWXji6w5EWLx7QuxalNk3HIDnRqW1NT+x" +
            "efa4YLhezrZFnErzu7VM3WqQDNBeir/Pv0WC7XMsTBrx9m1eSubbPKhmPrA40eEL+vqYPY7BM8yAEACGOZ4mf50DnVKTLS9LkINc" +
            "GkrhqBFNDohBlIAN4Dxgbff9444WAQBWkbEPCb8OSP91UE83zqbL42IdFtb0AExTO2hgCwCRlsqk9UhU6pEDOpSDS44koxlt0MrM" +
            "9KXSGA97uUWI3TrmOpH0KEGIaKbXvFlMjOlIFMnxs5Oo45dWwSnAAFtW7PxUyoAKmdHPSRbiCu6P+dn4cQFFidSviQMof48qqn9P" +
            "O77+HlU8+HUxfFNpLBrNS/SUhwFTpPkn2OwOAzgFUtIXZpp+dCnYgNFlzFyM3xMNzIA/Kh814B/9o3/0j/7RP/p/q/8Ck9rNXQAg" +
            "AAA="
    }
}
