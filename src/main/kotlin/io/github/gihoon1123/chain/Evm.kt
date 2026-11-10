package io.github.gihoon1123.chain

import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt256
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.datatypes.Wei
import org.hyperledger.besu.evm.EvmSpecVersion
import org.hyperledger.besu.evm.fluent.EVMExecutor
import org.hyperledger.besu.evm.fluent.EvmSpec
import org.hyperledger.besu.evm.fluent.SimpleWorld
import org.hyperledger.besu.evm.frame.MessageFrame
import org.hyperledger.besu.evm.tracing.OperationTracer
import java.math.BigInteger

/** Executes real Cancun bytecode; native transaction accounting is performed by the caller. */
class Evm {
    val world = SimpleWorld()
    private val spec = EvmSpec.evmSpec(EvmSpecVersion.CANCUN, BigInteger.valueOf(CHAIN_ID))

    data class Result(
        val success: Boolean,
        val output: String,
        val gasUsed: Long,
        val logs: List<Log>,
        val error: String?,
    )

    fun setBalance(
        address: String,
        amount: BigInteger,
    ) {
        val target = Address.fromHexString(address)
        (world.getAccount(target) ?: world.createAccount(target, 0, Wei.ZERO)).setBalance(Wei.of(amount))
    }

    fun setCode(
        address: String,
        code: String,
    ) {
        val target = Address.fromHexString(address)
        (world.getAccount(target) ?: world.createAccount(target, 0, Wei.ZERO)).setCode(Bytes.fromHexString(code))
    }

    fun setNonce(
        address: String,
        nonce: Long,
    ) {
        val target = Address.fromHexString(address)
        (world.getAccount(target) ?: world.createAccount(target, nonce, Wei.ZERO)).setNonce(nonce)
    }

    fun setStorage(
        address: String,
        slot: BigInteger,
        value: BigInteger,
    ) {
        val target = Address.fromHexString(address)
        (
            world.getAccount(
                target,
            ) ?: world.createAccount(target, 0, Wei.ZERO)
        ).setStorageValue(UInt256.valueOf(slot), UInt256.valueOf(value))
    }

    fun accounts(): List<org.hyperledger.besu.evm.account.Account> = world.touchedAccounts.toList()

    fun code(address: String): String = world.get(Address.fromHexString(address))?.code?.toHexString() ?: "0x"

    fun storage(
        address: String,
        slot: BigInteger,
    ): BigInteger = world.get(Address.fromHexString(address))?.getStorageValue(UInt256.valueOf(slot))?.toBigInteger() ?: BigInteger.ZERO

    fun execute(
        sender: String,
        receiver: String,
        code: String,
        input: String = "0x",
        gas: Long = 10_000_000,
        value: BigInteger = BigInteger.ZERO,
        creation: Boolean = false,
        readOnly: Boolean = false,
        blockNumber: Long = 0,
        timestamp: Long =
            System.currentTimeMillis() /
                1000,
    ): Result {
        val update = world.updater()
        var finalFrame: MessageFrame? = null
        val tracer =
            object : OperationTracer {
                override fun traceContextExit(frame: MessageFrame) {
                    if (frame.depth == 0) finalFrame = frame
                }
            }
        val executor =
            EVMExecutor(spec)
                .worldUpdater(update)
                .sender(Address.fromHexString(sender))
                .receiver(Address.fromHexString(receiver))
                .contract(Address.fromHexString(receiver))
                .code(Bytes.fromHexString(code))
                .callData(Bytes.fromHexString(input))
                .ethValue(Wei.of(value))
                .gas(gas)
                .timestamp(timestamp)
                .number(blockNumber)
                .gasLimit(30_000_000)
                .tracer(tracer)
                .messageFrameType(if (creation) MessageFrame.Type.CONTRACT_CREATION else MessageFrame.Type.MESSAGE_CALL)
                .commitWorldState(false)
        executor.execute()
        val frame = requireNotNull(finalFrame) { "Missing top-level execution result" }
        val success = frame.state == MessageFrame.State.COMPLETED_SUCCESS
        if (success && !readOnly) update.commit() else update.revert()
        val logs =
            if (success) {
                frame.logs.map {
                    Log(
                        it.logger.toHexString(),
                        it.topics.map { topic ->
                            topic.toHexString()
                        },
                        it.data.toHexString(),
                    )
                }
            } else {
                emptyList()
            }
        return Result(
            success,
            frame.outputData.toHexString(),
            gas - frame.remainingGas,
            logs,
            frame.exceptionalHaltReason.map { it.toString() }.orElse(null),
        )
    }
}
