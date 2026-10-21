#pragma once

#include <QDialog>
#include <QVector>

#include <functional>

#include "audioforge/smart_playlists.hpp"

class QCheckBox;
class QComboBox;
class QLabel;
class QLineEdit;
class QPushButton;
class QSpinBox;
class QTimer;
class QVBoxLayout;

namespace audioforge {

// New Smart Playlist / Edit Rules: the name, the rules (field, operator,
// value), all or any, a limit and the sort order -- with a live count of
// the songs that match.
class SmartPlaylistDialog : public QDialog
{
public:
    // `countMatches` counts the library's songs that match a playlist.
    SmartPlaylistDialog(const SmartPlaylist& initial, std::function<int(const SmartPlaylist&)> countMatches,
                        QWidget* parent = nullptr);

    SmartPlaylist result() const;

private:
    struct RuleRow
    {
        QWidget* row;
        QComboBox* field;
        QComboBox* op;
        QLineEdit* value;
    };

    void addRuleRow(const SmartRule& rule);
    void fillOperators(const RuleRow& row, SmartOperator selected);
    void removeRuleRow(QWidget* row);
    void scheduleCount();
    void updateCount();

    SmartPlaylist m_initial;
    std::function<int(const SmartPlaylist&)> m_countMatches;
    QLineEdit* m_name = nullptr;
    QComboBox* m_match = nullptr;
    QVBoxLayout* m_rulesLayout = nullptr;
    QVector<RuleRow> m_rows;
    QCheckBox* m_limitCheck = nullptr;
    QSpinBox* m_limit = nullptr;
    QComboBox* m_sort = nullptr;
    QLabel* m_count = nullptr;
    QPushButton* m_save = nullptr;
    QTimer* m_countTimer = nullptr;
};

} // namespace audioforge
