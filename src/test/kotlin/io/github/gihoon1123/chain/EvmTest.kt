package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class EvmTest {
    private val sender = "0x" + "11".repeat(20)
    private val contract = "0x" + "22".repeat(20)

    @Test fun `Cancun executes storage write and returns encoded integer`() {
        val vm = Evm()
        vm.setBalance(sender, WEI)
        vm.setCode(contract, "0x602a60005560005460005260206000f3")
        val result = vm.execute(sender, contract, vm.code(contract))
        assertTrue(result.success)
        assertEquals("0x" + "00".repeat(31) + "2a", result.output)
        assertEquals(BigInteger.valueOf(42), vm.storage(contract, BigInteger.ZERO))
        assertTrue(result.gasUsed > 0)
    }

    @Test fun `revert undoes storage mutations`() {
        val vm = Evm()
        vm.setBalance(sender, WEI)
        vm.setCode(contract, "0x602a60005560006000fd")
        val result = vm.execute(sender, contract, vm.code(contract))
        assertFalse(result.success)
        assertEquals(BigInteger.ZERO, vm.storage(contract, BigInteger.ZERO))
    }

    @Test fun `read only call does not commit writes`() {
        val vm = Evm()
        vm.setBalance(sender, WEI)
        vm.setCode(contract, "0x602a60005500")
        assertTrue(vm.execute(sender, contract, vm.code(contract), readOnly = true).success)
        assertEquals(BigInteger.ZERO, vm.storage(contract, BigInteger.ZERO))
    }
}
