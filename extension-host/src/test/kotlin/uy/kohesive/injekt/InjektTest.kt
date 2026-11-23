package uy.kohesive.injekt

import com.theoriacodex.extensionhost.assertFailsWith
import org.junit.Assert.assertSame
import org.junit.Test
import uy.kohesive.injekt.api.InjektionException

class InjektTest {
    private class Bound

    private class Unbound

    @Test
    fun `a bound type is returned by getInstance`() {
        val instance = Bound()
        Injekt.bind(Bound::class.java, instance)

        assertSame(instance, Injekt.getInstance<Bound>(Bound::class.java))
    }

    @Test
    fun `an unbound type throws InjektionException`() {
        assertFailsWith<InjektionException> { Injekt.getInstance<Unbound>(Unbound::class.java) }
    }
}
