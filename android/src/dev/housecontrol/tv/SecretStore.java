package dev.housecontrol.tv;

import org.json.JSONObject;

/** Where a client keeps its credential: SecureVault on the TV, memory in tests. */
interface SecretStore {
    JSONObject read()throws Exception;
    void write(JSONObject value)throws Exception;
    void clear();
}
