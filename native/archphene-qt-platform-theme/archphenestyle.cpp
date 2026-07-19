#include <QAbstractSpinBox>
#include <QApplication>
#include <QDialog>
#include <QFont>
#include <QLabel>
#include <QLineEdit>
#include <QProxyStyle>
#include <QStatusBar>
#include <QStyleFactory>
#include <QStylePlugin>

namespace {

class ArchpheneStyle final : public QProxyStyle
{
public:
    ArchpheneStyle()
        : QProxyStyle(QStyleFactory::create(QStringLiteral("fusion")))
    {
    }

    void polish(QWidget *widget) override
    {
        QProxyStyle::polish(widget);
        if (auto *statusBar = qobject_cast<QStatusBar *>(widget)) {
            const int padding = qMax(6, statusBar->fontMetrics().height() / 3);
            statusBar->setMinimumHeight(statusBar->fontMetrics().height() + padding);
            return;
        }
        if (auto *label = qobject_cast<QLabel *>(widget);
                label && qobject_cast<QStatusBar *>(label->parentWidget())) {
            const int padding = qMax(6, label->fontMetrics().height() / 3);
            label->setFixedHeight(label->fontMetrics().height() + padding);
            return;
        }
        auto *editor = qobject_cast<QLineEdit *>(widget);
        if (!editor || qobject_cast<QAbstractSpinBox *>(editor->parentWidget())
                || qobject_cast<QDialog *>(editor->window())) {
            return;
        }

        bool pointSizeOk = false;
        const int basePointSize = qEnvironmentVariableIntValue(
                "ARCHPHENE_FONT_POINT_SIZE", &pointSizeOk);
        if (!pointSizeOk || basePointSize <= 0) {
            return;
        }
        const int editorPointSize = qMin(26, (basePointSize * 4 + 2) / 3);
        if (editor->font().pointSize() >= editorPointSize) {
            return;
        }
        QFont font = editor->font();
        font.setPointSize(editorPointSize);
        editor->setFont(font);
    }
};

class ArchpheneStylePlugin final : public QStylePlugin
{
    Q_OBJECT
    Q_PLUGIN_METADATA(IID QStyleFactoryInterface_iid FILE "archphenestyle.json")

public:
    QStyle *create(const QString &key) override
    {
        if (key.compare(QLatin1String("archphene"), Qt::CaseInsensitive) != 0) {
            return nullptr;
        }
        return new ArchpheneStyle;
    }
};

} // namespace

#include "archphenestyle.moc"
