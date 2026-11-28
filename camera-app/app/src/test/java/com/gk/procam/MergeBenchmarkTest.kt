package com.gk.procam

import org.junit.Test
import java.util.Random

class MergeBenchmarkTest {
    @Test
    fun fullResolutionTiming() {
        val w = 4080; val h = 3072
        val rnd = Random(1)
        val base = IntArray(w * h) { i -> val v = ((i % w) * 7 + (i / w) * 3 + rnd.nextInt(30)) and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val ref = base.copyOf()
        val t0 = System.nanoTime()
        Processing.merge(ref, w, h, 5, iso = 800) { _, dst -> base.copyInto(dst); true }
        val t1 = System.nanoTime()
        Processing.finish(ref, w, h, MergeKind.FUSION)
        val t2 = System.nanoTime()
        println("12.5MP: merge 5 extra frames %.2fs, tone+sharpen %.2fs".format((t1 - t0) / 1e9, (t2 - t1) / 1e9))
    }
}
