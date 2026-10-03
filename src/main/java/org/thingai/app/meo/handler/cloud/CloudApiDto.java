package org.thingai.app.meo.handler.cloud;

public final class CloudApiDto {
    private CloudApiDto() {
    }

    // POST /edges/register
    public static final class RegisterReq {
        public final String mac;
        public final String claimCode;

        public RegisterReq(String mac, String claimCode) {
            this.mac = mac;
            this.claimCode = claimCode;
        }
    }

    public static final class RegisterRes {
        public String edgeId;
        public String secret;
    }

    // POST /edges/status
    public static final class StatusReq {
        public final String mac;
        public final String secret;

        public StatusReq(String mac, String secret) {
            this.mac = mac;
            this.secret = secret;
        }
    }

    public static final class StatusRes {
        public String edgeId;
        public boolean claimed;
    }
}
