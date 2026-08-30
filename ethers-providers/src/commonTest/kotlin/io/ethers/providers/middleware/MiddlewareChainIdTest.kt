package io.ethers.providers.middleware

import io.ethers.providers.Provider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression test for the duplicate `chainId` API: [EthApi] used to declare both `val chainId: Long` and
 * `fun getChainId(): RpcRequest<Long, RpcError>`, which compiled to two `getChainId()` JVM methods differing
 * only in return type and was therefore unresolvable from Java. The chain id now lives on [Middleware] as a
 * plain property, and these tests pin it there.
 */
class MiddlewareChainIdTest : FunSpec({
    test("chain id is reachable through a Middleware-typed reference") {
        val middleware: Middleware = Provider.builder("https://localhost:1/nonexistent").build(1L).unwrap()

        middleware.chainId shouldBe 1L
    }

    test("chain id is carried through a delegating middleware") {
        val provider = Provider.builder("https://localhost:1/nonexistent").build(7L).unwrap()
        val delegating = DelegatingMiddleware(provider)

        delegating.chainId shouldBe 7L
    }
})

private class DelegatingMiddleware(override val inner: Middleware) : Middleware by inner
