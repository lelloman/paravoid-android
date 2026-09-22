/* Installed-only ABI marker. No app code, JNI hooks, dependencies or mutable data.
 * Keeps PackageManager ABI selection intact when app libraries move to a payload.
 */
void paravoid_abi_marker_v1(void) {}
