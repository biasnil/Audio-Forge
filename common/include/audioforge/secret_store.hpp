#pragma once

#include <QString>

namespace audioforge {

// Wraps a secret (e.g. the Musixmatch API key) for storage in
// AudioForge.ini so it isn't sitting there in plain text. On Windows this
// is DPAPI (CryptProtectData): the stored value can only be decrypted by
// the same Windows user account, so copying the .ini elsewhere doesn't
// leak the key. Other platforms have no equivalent built into the OS
// without an extra dependency (libsecret/Keychain), so there the value is
// stored as-is.
//
// UnprotectSecret() also accepts a plain, never-protected value -- that's
// how an older AudioForge.ini with a plain-text key keeps working; it gets
// re-saved protected on the next settings save.
QString ProtectSecret(const QString& plainText);
QString UnprotectSecret(const QString& storedValue);

} // namespace audioforge
