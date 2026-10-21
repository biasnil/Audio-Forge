#include "audioforge/dialogs/smart_playlist_dialog.hpp"

#include <QCheckBox>
#include <QComboBox>
#include <QDialogButtonBox>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QScrollArea>
#include <QSpinBox>
#include <QTimer>
#include <QVBoxLayout>

namespace audioforge {

namespace {

const SmartField kFields[] = {SmartField::Title, SmartField::Artist, SmartField::Album, SmartField::Genre,
                              SmartField::Year, SmartField::PlayCount, SmartField::LastPlayedDays,
                              SmartField::AddedDays, SmartField::Loved, SmartField::Bpm, SmartField::Key,
                              SmartField::Minutes};
const SmartSort kSorts[] = {SmartSort::Title, SmartSort::MostPlayed, SmartSort::RecentlyPlayed,
                            SmartSort::RecentlyAdded, SmartSort::Random, SmartSort::Bpm};

} // namespace

SmartPlaylistDialog::SmartPlaylistDialog(const SmartPlaylist& initial,
                                         std::function<int(const SmartPlaylist&)> countMatches, QWidget* parent)
    : QDialog(parent), m_initial(initial), m_countMatches(std::move(countMatches))
{
    setWindowTitle(initial.name.isEmpty() ? tr("New Smart Playlist") : tr("Edit Rules"));
    resize(640, 520);

    auto* layout = new QVBoxLayout(this);
    auto* form = new QFormLayout();
    m_name = new QLineEdit(initial.name);
    m_name->setPlaceholderText(tr("Name"));
    form->addRow(tr("Name"), m_name);
    m_match = new QComboBox();
    m_match->addItems({tr("All rules"), tr("Any rule")});
    m_match->setCurrentIndex(initial.matchAll ? 0 : 1);
    form->addRow(tr("Match"), m_match);
    layout->addLayout(form);

    auto* rulesTitle = new QLabel(tr("Rules"));
    rulesTitle->setObjectName("SettingsSectionTitle");
    layout->addWidget(rulesTitle);

    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* rulesHost = new QWidget();
    m_rulesLayout = new QVBoxLayout(rulesHost);
    m_rulesLayout->setContentsMargins(0, 0, 0, 0);
    m_rulesLayout->addStretch(); // rows go in before this
    scroll->setWidget(rulesHost);
    layout->addWidget(scroll, 1);

    auto* addRule = new QPushButton(tr("+ Add Rule"));
    addRule->setObjectName("LinkButton");
    addRule->setCursor(Qt::PointingHandCursor);
    connect(addRule, &QPushButton::clicked, this, [this]() {
        addRuleRow(SmartRule());
        scheduleCount();
    });
    auto* addRow = new QHBoxLayout();
    addRow->addWidget(addRule);
    addRow->addStretch();
    layout->addLayout(addRow);

    auto* options = new QFormLayout();
    auto* limitRow = new QHBoxLayout();
    m_limitCheck = new QCheckBox(tr("Limit"));
    m_limit = new QSpinBox();
    m_limit->setRange(1, 100000);
    m_limit->setValue(initial.limit > 0 ? initial.limit : 25);
    m_limit->setSuffix(QStringLiteral(" ") + tr("songs"));
    m_limitCheck->setChecked(initial.limit > 0);
    m_limit->setEnabled(initial.limit > 0);
    connect(m_limitCheck, &QCheckBox::toggled, m_limit, &QWidget::setEnabled);
    limitRow->addWidget(m_limitCheck);
    limitRow->addWidget(m_limit);
    limitRow->addStretch();
    options->addRow(limitRow);
    m_sort = new QComboBox();
    for (SmartSort sort : kSorts)
    {
        m_sort->addItem(SmartSortName(sort), static_cast<int>(sort));
    }
    m_sort->setCurrentIndex(qMax(0, m_sort->findData(static_cast<int>(initial.sort))));
    options->addRow(tr("Sort By"), m_sort);
    layout->addLayout(options);

    m_count = new QLabel();
    m_count->setObjectName("MutedLabel");
    layout->addWidget(m_count);
    auto* footer = new QLabel(tr("Smart playlists update by themselves as your library, play counts and loved songs change."));
    footer->setObjectName("SettingsFooter");
    footer->setWordWrap(true);
    layout->addWidget(footer);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Save | QDialogButtonBox::Cancel);
    m_save = buttons->button(QDialogButtonBox::Save);
    connect(buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    layout->addWidget(buttons);

    m_countTimer = new QTimer(this);
    m_countTimer->setSingleShot(true);
    m_countTimer->setInterval(150);
    connect(m_countTimer, &QTimer::timeout, this, &SmartPlaylistDialog::updateCount);

    for (const SmartRule& rule : initial.rules)
    {
        addRuleRow(rule);
    }
    if (initial.rules.isEmpty())
    {
        addRuleRow(SmartRule());
    }

    connect(m_name, &QLineEdit::textChanged, this, [this](const QString& text) {
        m_save->setEnabled(!text.trimmed().isEmpty());
    });
    m_save->setEnabled(!m_name->text().trimmed().isEmpty());
    connect(m_match, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &SmartPlaylistDialog::scheduleCount);
    connect(m_limitCheck, &QCheckBox::toggled, this, &SmartPlaylistDialog::scheduleCount);
    connect(m_limit, QOverload<int>::of(&QSpinBox::valueChanged), this, &SmartPlaylistDialog::scheduleCount);
    updateCount();
}

void SmartPlaylistDialog::addRuleRow(const SmartRule& rule)
{
    auto* row = new QWidget();
    auto* rowLayout = new QHBoxLayout(row);
    rowLayout->setContentsMargins(0, 2, 0, 2);
    RuleRow r{row, new QComboBox(), new QComboBox(), new QLineEdit()};
    for (SmartField field : kFields)
    {
        r.field->addItem(SmartFieldName(field), static_cast<int>(field));
    }
    r.field->setCurrentIndex(qMax(0, r.field->findData(static_cast<int>(rule.field))));
    r.value->setText(rule.value);
    fillOperators(r, rule.op);

    auto* remove = new QPushButton(QChar(0x2715)); // ×
    remove->setObjectName("IconButton");
    remove->setFixedSize(28, 28);
    remove->setToolTip(tr("Remove"));
    remove->setCursor(Qt::PointingHandCursor);

    rowLayout->addWidget(r.field, 3);
    rowLayout->addWidget(r.op, 2);
    rowLayout->addWidget(r.value, 3);
    rowLayout->addWidget(remove);
    m_rulesLayout->insertWidget(m_rulesLayout->count() - 1, row);
    m_rows << r;

    connect(r.field, QOverload<int>::of(&QComboBox::currentIndexChanged), this, [this, row]() {
        for (const RuleRow& existing : m_rows)
        {
            if (existing.row == row)
            {
                fillOperators(existing, SmartOperator::Contains);
            }
        }
        scheduleCount();
    });
    connect(r.op, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &SmartPlaylistDialog::scheduleCount);
    connect(r.value, &QLineEdit::textChanged, this, &SmartPlaylistDialog::scheduleCount);
    connect(remove, &QPushButton::clicked, this, [this, row]() { removeRuleRow(row); });
}

void SmartPlaylistDialog::fillOperators(const RuleRow& row, SmartOperator selected)
{
    const auto field = static_cast<SmartField>(row.field->currentData().toInt());
    const QVector<SmartOperator> operators = OperatorsFor(field);
    row.op->blockSignals(true);
    row.op->clear();
    for (SmartOperator op : operators)
    {
        row.op->addItem(SmartOperatorName(op), static_cast<int>(op));
    }
    row.op->setCurrentIndex(qMax(0, row.op->findData(static_cast<int>(selected))));
    row.op->blockSignals(false);
    // Yes/no rules (Loved) take no value; numbers get a hint.
    row.value->setVisible(KindOf(field) != SmartFieldKind::Bool);
    row.value->setPlaceholderText(KindOf(field) == SmartFieldKind::Number ? tr("Number") : tr("Text"));
}

void SmartPlaylistDialog::removeRuleRow(QWidget* row)
{
    for (int i = 0; i < m_rows.size(); ++i)
    {
        if (m_rows[i].row == row)
        {
            m_rows.removeAt(i);
            break;
        }
    }
    row->deleteLater();
    scheduleCount();
}

SmartPlaylist SmartPlaylistDialog::result() const
{
    SmartPlaylist p = m_initial;
    p.name = m_name->text().trimmed();
    p.matchAll = m_match->currentIndex() == 0;
    p.limit = m_limitCheck->isChecked() ? m_limit->value() : 0;
    p.sort = static_cast<SmartSort>(m_sort->currentData().toInt());
    p.rules.clear();
    for (const RuleRow& r : m_rows)
    {
        SmartRule rule;
        rule.field = static_cast<SmartField>(r.field->currentData().toInt());
        rule.op = static_cast<SmartOperator>(r.op->currentData().toInt());
        rule.value = KindOf(rule.field) == SmartFieldKind::Bool ? QString() : r.value->text().trimmed();
        p.rules << rule;
    }
    return p;
}

void SmartPlaylistDialog::scheduleCount()
{
    m_countTimer->start();
}

void SmartPlaylistDialog::updateCount()
{
    if (m_countMatches)
    {
        m_count->setText(tr("%1 songs match").arg(m_countMatches(result())));
    }
}

} // namespace audioforge
