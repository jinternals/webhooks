package com.jinternals.webhooks.producer.fixtures.defaultpkg;

public final class PackagePrivateDefaultClientFixture {
    private PackagePrivateDefaultClientFixture() {}

    interface PackagePrivateDefaultClient {
        void send(Object event);

        default String helper(String prefix) {
            return prefix + ":ok";
        }
    }

    public static Class<?> clientInterface() {
        return PackagePrivateDefaultClient.class;
    }
}
