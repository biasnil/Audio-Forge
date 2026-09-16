#include "audioforge/track_metadata_tags.hpp"

#include <taglib/fileref.h>
#include <taglib/tpropertymap.h>
#include <taglib/tstring.h>

namespace audioforge {

namespace {

QString ToQString(const TagLib::String& s)
{
    return QString::fromStdWString(s.toWString());
}

TagLib::String ToTagString(const QString& s)
{
    return TagLib::String(s.toStdWString());
}

} // namespace

QMap<QString, QString> ReadAllTags(const QString& path)
{
    QMap<QString, QString> result;

    std::wstring pathW = path.toStdWString();
    TagLib::FileRef file(pathW.c_str());
    if (file.isNull() || !file.tag())
    {
        return result;
    }

    TagLib::PropertyMap properties = file.file()->properties();
    for (auto it = properties.begin(); it != properties.end(); ++it)
    {
        QString key = ToQString(it->first);
        QStringList values;
        for (const auto& value : it->second)
        {
            values << ToQString(value);
        }
        result[key] = values.join("; ");
    }
    return result;
}

bool WriteAllTags(const QString& path, const QMap<QString, QString>& tags)
{
    std::wstring pathW = path.toStdWString();
    TagLib::FileRef file(pathW.c_str());
    if (file.isNull() || !file.file())
    {
        return false;
    }

    TagLib::PropertyMap properties;
    for (auto it = tags.constBegin(); it != tags.constEnd(); ++it)
    {
        // "; "-joined values (see ReadAllTags) are split back apart so a
        // multi-value key round-trips instead of collapsing into one
        // literal string containing "; ".
        const QStringList parts = it.value().split("; ", Qt::SkipEmptyParts);
        TagLib::StringList values;
        if (parts.isEmpty())
        {
            values.append(ToTagString(it.value()));
        }
        else
        {
            for (const QString& part : parts)
            {
                values.append(ToTagString(part));
            }
        }
        properties.replace(ToTagString(it.key()), values);
    }

    file.file()->setProperties(properties);
    return file.save();
}

} // namespace audioforge
