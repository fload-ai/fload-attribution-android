package com.fload.attribution

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.fload.attribution.core.ReferrerResult
import com.fload.attribution.core.ReferrerSnapshot
import com.fload.attribution.core.ReferrerSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Google's Play Install Referrer API, read once on the SDK's thread: it
 * connects, waits at most 10 s for Play's answer, and disconnects.
 */
internal class PlayInstallReferrer(private val context: Context) : ReferrerSource {
    override fun read(): ReferrerResult {
        val client = try {
            InstallReferrerClient.newBuilder(context).build()
        } catch (error: Exception) {
            return ReferrerResult.Failed
        }
        val answer = AtomicReference<ReferrerResult>(ReferrerResult.Retryable)
        val done = CountDownLatch(1)
        return try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    answer.set(resultOf(client, responseCode))
                    done.countDown()
                }

                override fun onInstallReferrerServiceDisconnected() {
                    done.countDown()
                }
            })
            if (done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) answer.get() else ReferrerResult.Retryable
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ReferrerResult.Retryable
        } catch (error: Exception) {
            ReferrerResult.Failed
        } finally {
            try {
                client.endConnection()
            } catch (error: Exception) {
                // Already gone.
            }
        }
    }

    private fun resultOf(client: InstallReferrerClient, responseCode: Int): ReferrerResult = when (responseCode) {
        InstallReferrerResponse.OK -> try {
            val details = client.installReferrer
            ReferrerResult.Read(
                ReferrerSnapshot.fromPlay(
                    referrer = details.installReferrer,
                    clickAtSeconds = details.referrerClickTimestampSeconds,
                    installBeginAtSeconds = details.installBeginTimestampSeconds,
                    clickServerAtSeconds = details.referrerClickTimestampServerSeconds,
                    installBeginServerAtSeconds = details.installBeginTimestampServerSeconds,
                    installVersion = details.installVersion,
                    googlePlayInstant = details.googlePlayInstantParam,
                ),
            )
        } catch (error: Exception) {
            ReferrerResult.Retryable
        }
        InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> ReferrerResult.NotSupported
        InstallReferrerResponse.SERVICE_UNAVAILABLE,
        InstallReferrerResponse.SERVICE_DISCONNECTED,
        -> ReferrerResult.Retryable
        else -> ReferrerResult.Failed
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
