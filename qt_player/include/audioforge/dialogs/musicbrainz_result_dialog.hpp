#pragma once

#include <QDialog>
#include <QVector>
#include <QString>

class QListWidget;

namespace audioforge {

struct MusicBrainzCandidate
{
    QString title;
    QString artist;
    QString album;
    unsigned int year = 0;
};

// Shows the candidates returned by a MusicBrainz search so the person can
// pick the right one (or cancel) rather than tags getting overwritten with
// an auto-picked, possibly wrong, top match.
class MusicBrainzResultDialog : public QDialog
{
    Q_OBJECT

public:
    MusicBrainzResultDialog(const QVector<MusicBrainzCandidate>& candidates, QWidget* parent = nullptr);

    int selectedIndex() const;

private:
    QListWidget* m_list = nullptr;
};

} // namespace audioforge
