package org.plukh.mcpproxy.oauth

import org.plukh.mcpproxy.config.OAuthConfig

/**
 * How one authorization flow receives its redirect back from the browser.
 *
 * Two deployments need genuinely different answers. A CLI run owns nothing permanent, so it binds a
 * loopback listener for the duration of the flow and takes whatever port it is given. A long-running
 * server already owns a listener on a fixed port, cannot bind a second one there, and - more
 * usefully - can offer a redirect URI that is the *same every time*, which is what stops
 * authorization servers accumulating a client registration per login.
 *
 * The seam exists because that difference reaches surprisingly deep: with an ephemeral port the
 * redirect URI does not exist until the listener is bound, which is why registration is a callback
 * in [AuthorizationFlow.authorize] rather than something computed up front.
 */
interface CallbackTransport {

    /** Prepares to receive the callback for one flow, identified by its single-use [state]. */
    suspend fun begin(state: String): Handle

    interface Handle : AutoCloseable {
        /** What to register and to send as `redirect_uri`. Known as soon as the handle exists. */
        val redirectUri: String

        /** Suspends until the browser comes back. Never completes on a wrong-state request. */
        suspend fun await(): AuthorizationCallback
    }
}

/** The CLI's transport: one loopback listener per flow, torn down with it. */
class EphemeralCallbackTransport(private val oauth: OAuthConfig) : CallbackTransport {

    override suspend fun begin(state: String): CallbackTransport.Handle {
        val server = CallbackServer(oauth.callbackBindHost, oauth.callbackPort, oauth.callbackUrl, state)
        server.start()
        return object : CallbackTransport.Handle {
            override val redirectUri: String = server.redirectUri
            override suspend fun await(): AuthorizationCallback = server.await()
            override fun close() = server.close()
        }
    }
}
