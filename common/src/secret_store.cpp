#include "audioforge/secret_store.hpp"

#include <QByteArray>

#ifdef _WIN32
#include <windows.h>
#include <wincrypt.h> // CryptProtectData/CryptUnprotectData (DPAPI)
#endif

namespace audioforge {

namespace {
// Marks a value as DPAPI-protected, so UnprotectSecret() can tell it apart
// from a legacy plain-text value.
const QString kProtectedPrefix = QStringLiteral("dpapi:");
}

QString ProtectSecret(const QString& plainText)
{
#ifdef _WIN32
    if (plainText.isEmpty())
    {
        return QString();
    }

    QByteArray plainBytes = plainText.toUtf8();
    DATA_BLOB input;
    input.cbData = static_cast<DWORD>(plainBytes.size());
    input.pbData = reinterpret_cast<BYTE*>(plainBytes.data());
    DATA_BLOB output{};

    if (!CryptProtectData(&input, L"AudioForge", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output))
    {
        return plainText; // protection unavailable -- better stored plain than lost
    }
    QByteArray encrypted(reinterpret_cast<const char*>(output.pbData), static_cast<int>(output.cbData));
    LocalFree(output.pbData);
    return kProtectedPrefix + QString::fromLatin1(encrypted.toBase64());
#else
    return plainText;
#endif
}

QString UnprotectSecret(const QString& storedValue)
{
    if (!storedValue.startsWith(kProtectedPrefix))
    {
        return storedValue; // legacy plain-text value (or a non-Windows build)
    }

#ifdef _WIN32
    QByteArray encrypted = QByteArray::fromBase64(storedValue.mid(kProtectedPrefix.size()).toLatin1());
    DATA_BLOB input;
    input.cbData = static_cast<DWORD>(encrypted.size());
    input.pbData = reinterpret_cast<BYTE*>(encrypted.data());
    DATA_BLOB output{};

    if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output))
    {
        return QString(); // e.g. the .ini was copied from another Windows account
    }
    QString plainText = QString::fromUtf8(reinterpret_cast<const char*>(output.pbData), static_cast<int>(output.cbData));
    SecureZeroMemory(output.pbData, output.cbData);
    LocalFree(output.pbData);
    return plainText;
#else
    return QString(); // protected on Windows; can't be decrypted here
#endif
}

} // namespace audioforge
