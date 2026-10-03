package org.thingai.app.meo.handler.cloud;

import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.util.DaoKvUtil;
import org.thingai.app.meo.util.NetUtil;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;


public class CloudRegister {
    private static final String TAG = "CloudRegister";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String CLAIM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CLAIM_CODE_LENGTH = 8;
    // The cloud's claim window is 10 min; poll past it so a claim made in its last seconds is still seen.
    private static final long CLAIM_WINDOW_NS = TimeUnit.MINUTES.toNanos(11);

    private static final long POLL_MS = 30_000;

    private static final String KV_EDGE_ID = "cloud.edgeId";
    private static final String KV_SECRET = "cloud.secret";

    private final Dao dao;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private Thread thread;
    private String claimCode;
    private long registeredAt;

    private String edgeId;
    private String secret;
    private CloudRegisterState state = CloudRegisterState.INITIAL;

    public CloudRegister(Dao dao) {
        this.dao = dao;
        edgeId = DaoKvUtil.get(dao, KV_EDGE_ID);
        secret = DaoKvUtil.get(dao, KV_SECRET);
    }

    public String edgeId() {
        return edgeId;
    }

    public String secret() {
        return secret;
    }

    public void startCloudRegistration(CloudRegisterCallback callback) {
        thread = new Thread(() -> {
            verifyIdentity();
            if (state == CloudRegisterState.UNREGISTERED) {
                requestRegister();
            }
            try {
                while (state == CloudRegisterState.REGISTERED) {
                    Thread.sleep(POLL_MS);
                    pollClaim();
                }
            } catch (InterruptedException e) {
                // stop()
                return;
            }
            if (state == CloudRegisterState.CLAIMED) {
                callback.onRegisterSuccess(edgeId, secret);
            } else {
                callback.onRegisterFailed(MeoErr.CLOUD_ERR, "cloud registration ended in " + state);
            }
        }, "meo-cloud");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void verifyIdentity() {
        state = CloudRegisterState.INITIAL;
        if (secret == null) {
            state = CloudRegisterState.UNREGISTERED;
            claimCode = generateClaimCode();
            ILog.i(TAG, "verifyIdentity", "unregistered", "claimCode=" + claimCode);

            // this does not have to call the cloud for identity anymore, request register immediately
            return;
        }

        String macAddr = NetUtil.lanMac();
        if (macAddr == null) {
            ILog.e(TAG, "verifyIdentity", "get mac addr failed");
            state = CloudRegisterState.FAILED;
            return;
        }
        ILog.d(TAG, "verifyIdentity", "mac=" + macAddr, "secret=" + "<redacted>");

        try {
            CloudApi.Reply<CloudApiDto.StatusRes> reply = CloudApi.status(macAddr, secret);
            if (!reply.ok()) {
                switch (reply.status) {
                    case 401:
                        state = CloudRegisterState.UNREGISTERED;
                        claimCode = generateClaimCode();
                        ILog.w(TAG, "verifyIdentity", "identity rejected", "claimCode=" + claimCode);

                        break;
                    case 404:
                        ILog.w(TAG, "verifyIdentity", "not found");
                        state = CloudRegisterState.FAILED;
                        break;
                    default:
                        ILog.w(TAG, "verifyIdentity", "unexpected status=" + reply.status);
                        state = CloudRegisterState.FAILED;
                }
                return;
            }

            if (reply.body.claimed) {
                state = CloudRegisterState.CLAIMED;
            } else {
                // Verify runs at boot or after a failure, when no claim code is live here, so register again.
                state = CloudRegisterState.UNREGISTERED;
                claimCode = generateClaimCode();
                ILog.i(TAG, "verifyIdentity", "unclaimed", "claimCode=" + claimCode);
            }

        } catch (Exception e) {
            ILog.w(TAG, "verifyIdentity", "failed", String.valueOf(e));
            state = CloudRegisterState.FAILED;
        }

    }

    private void requestRegister() {
        if (state != CloudRegisterState.UNREGISTERED) {
            ILog.w(TAG, "requestRegister", "ignored in state " + state);
            return;
        }

        String macAddr = NetUtil.lanMac();
        if (macAddr == null) {
            ILog.e(TAG, "requestRegister", "get mac addr failed");
            state = CloudRegisterState.FAILED;
            return;
        }

        try {
            CloudApi.Reply<CloudApiDto.RegisterRes> reply = CloudApi.register(macAddr, claimCode);
            if (!reply.ok()) {
                switch (reply.status) {
                    case 401:
                        ILog.w(TAG, "requestRegister", "identity rejected");
                        break;
                    case 409:
                        ILog.w(TAG, "requestRegister", "raced register");
                        break;
                    default:
                        ILog.w(TAG, "requestRegister", "unexpected status=" + reply.status);
                }
                state = CloudRegisterState.FAILED;
                return;
            }

            if (reply.body == null) {
                ILog.w(TAG, "requestRegister", "null response body");
                state = CloudRegisterState.FAILED;
                return;
            }

            saveIdentity(reply.body.edgeId, reply.body.secret);
            registeredAt = System.nanoTime();
            state = CloudRegisterState.REGISTERED;
            ILog.i(TAG, "requestRegister", "unclaimed", "mac=" + macAddr, "claimCode=" + claimCode);

        } catch (Exception e) {
            ILog.w(TAG, "requestRegister", "failed", String.valueOf(e));
            state = CloudRegisterState.FAILED;
        }
    }

    // Asks the cloud whether a user claimed the edge; the caller repeats it while the state stays REGISTERED.
    // Past the claim window nobody can claim any more, so it ends in EXPIRED and only a restart registers again.
    private void pollClaim() {
        if (state != CloudRegisterState.REGISTERED) {
            ILog.w(TAG, "pollClaim", "ignored in state " + state);
            return;
        }

        if (System.nanoTime() - registeredAt >= CLAIM_WINDOW_NS) {
            ILog.w(TAG, "pollClaim", "claim window closed, restart edge to register again");
            state = CloudRegisterState.EXPIRED;
            return;
        }

        // Anything but a clear answer keeps REGISTERED: the window already bounds the retries.
        String macAddr = NetUtil.lanMac();
        if (macAddr == null) {
            ILog.e(TAG, "pollClaim", "get mac addr failed");
            return;
        }

        try {
            CloudApi.Reply<CloudApiDto.StatusRes> reply = CloudApi.status(macAddr, secret);
            if (reply.status == 401) {
                // Edge was deleted in the cloud, or its secret re-issued.
                state = CloudRegisterState.UNREGISTERED;
                claimCode = generateClaimCode();
                ILog.w(TAG, "pollClaim", "identity rejected", "claimCode=" + claimCode);
                return;
            }
            if (!reply.ok() || reply.body == null) {
                ILog.w(TAG, "pollClaim", "unexpected status=" + reply.status);
                return;
            }
            if (reply.body.claimed) {
                state = CloudRegisterState.CLAIMED;
                ILog.i(TAG, "pollClaim", "claimed", "edgeId=" + edgeId);
            }
        } catch (Exception e) {
            ILog.w(TAG, "pollClaim", "failed", String.valueOf(e));
        }
    }

    public CloudRegisterState getState() {
        return state;
    }

    private void saveIdentity(String edgeId, String secret) {
        DaoKvUtil.put(dao, KV_EDGE_ID, edgeId);
        DaoKvUtil.put(dao, KV_SECRET, secret);
        this.edgeId = edgeId;
        this.secret = secret;

        ILog.i(TAG, "register", "identity stored", "edgeId=" + edgeId);
    }

    static String generateClaimCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder(CLAIM_CODE_LENGTH);
        for (int i = 0; i < CLAIM_CODE_LENGTH; i++) {
            code.append(CLAIM_CODE_CHARS.charAt(random.nextInt(CLAIM_CODE_CHARS.length())));
        }
        return code.toString();
    }
}
