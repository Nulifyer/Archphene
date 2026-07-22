#include <QAbstractSpinBox>
#include <QApplication>
#include <QDialog>
#include <QDir>
#include <QElapsedTimer>
#include <QFont>
#include <QLabel>
#include <QLineEdit>
#include <QProxyStyle>
#include <QSettings>
#include <QStyleOptionMenuItem>
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
        m_controlTimer.start();
    }

    int pixelMetric(PixelMetric metric, const QStyleOption *option = nullptr,
            const QWidget *widget = nullptr) const override
    {
        const int base = QProxyStyle::pixelMetric(metric, option, widget);
        const int target = controlTarget();
        const int visual = controlVisual();
        switch (metric) {
        case PM_ScrollBarExtent:
            return qMax(base, visual);
        case PM_SmallIconSize:
        case PM_ButtonIconSize:
        case PM_IndicatorWidth:
        case PM_IndicatorHeight:
        case PM_ExclusiveIndicatorWidth:
        case PM_ExclusiveIndicatorHeight:
            return qMax(base, visual);
        case PM_MenuHMargin:
        case PM_MenuVMargin:
            return qMax(base, target / 8);
        default:
            return base;
        }
    }

    QSize sizeFromContents(ContentsType type, const QStyleOption *option,
            const QSize &contentsSize, const QWidget *widget = nullptr) const override
    {
        QSize result = QProxyStyle::sizeFromContents(type, option, contentsSize, widget);
        const int target = controlTarget();
        switch (type) {
        case CT_MenuItem: {
            const auto *menuItem = qstyleoption_cast<const QStyleOptionMenuItem *>(option);
            const int minimum = menuItem
                    && menuItem->menuItemType == QStyleOptionMenuItem::Separator
                    ? qMax(8, target / 4) : target;
            result.setHeight(qMax(result.height(), minimum));
            break;
        }
        case CT_MenuBarItem:
            result.setHeight(qMax(result.height(), target));
            break;
        case CT_PushButton:
        case CT_ToolButton:
            result.setWidth(qMax(result.width(), target));
            result.setHeight(qMax(result.height(), target));
            break;
        case CT_ComboBox:
        case CT_LineEdit:
        case CT_SpinBox:
            result.setHeight(qMax(result.height(), target));
            break;
        default:
            break;
        }
        return result;
    }

    void polish(QWidget *widget) override
    {
        QProxyStyle::polish(widget);
        if (auto *statusBar = qobject_cast<QStatusBar *>(widget)) {
            statusBar->setMinimumHeight(qMax(controlTarget(),
                    statusBar->fontMetrics().height() + 8));
            const int horizontal = qMax(4, controlTarget() / 8);
            statusBar->setContentsMargins(horizontal, 0, horizontal, 0);
            return;
        }
        if (auto *label = qobject_cast<QLabel *>(widget);
                label && qobject_cast<QStatusBar *>(label->parentWidget())) {
            label->setMinimumHeight(qMax(controlTarget(),
                    label->fontMetrics().height() + 8));
            label->setMaximumHeight(QWIDGETSIZE_MAX);
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

private:
    int controlTarget() const
    {
        if (m_controlTarget > 0 && m_controlTimer.elapsed() < 250) {
            return m_controlTarget;
        }
        bool ok = false;
        int configured = qEnvironmentVariableIntValue(
                "ARCHPHENE_QT_CONTROL_MIN_SIZE", &ok);
        const QString configHome = qEnvironmentVariable("XDG_CONFIG_HOME");
        if (!configHome.isEmpty()) {
            QSettings settings(QDir(configHome).filePath(QStringLiteral("kdeglobals")),
                    QSettings::IniFormat);
            const QVariant value = settings.value(QStringLiteral("Archphene/ControlMinSize"));
            bool fileOk = false;
            const int fileTarget = value.toInt(&fileOk);
            if (fileOk) {
                configured = fileTarget;
                ok = true;
            }
        }
        m_controlTarget = ok ? qBound(24, configured, 128) : 40;
        m_controlTimer.restart();
        return m_controlTarget;
    }

    int controlVisual() const
    {
        if (m_controlVisual > 0 && m_visualTimer.elapsed() < 250) {
            return m_controlVisual;
        }
        bool ok = false;
        int configured = qEnvironmentVariableIntValue(
                "ARCHPHENE_QT_CONTROL_VISUAL_SIZE", &ok);
        const QString configHome = qEnvironmentVariable("XDG_CONFIG_HOME");
        if (!configHome.isEmpty()) {
            QSettings settings(QDir(configHome).filePath(QStringLiteral("kdeglobals")),
                    QSettings::IniFormat);
            const QVariant value = settings.value(
                    QStringLiteral("Archphene/ControlVisualSize"));
            bool fileOk = false;
            const int fileVisual = value.toInt(&fileOk);
            if (fileOk) {
                configured = fileVisual;
                ok = true;
            }
        }
        m_controlVisual = ok ? qBound(12, configured, 64) : 20;
        m_visualTimer.restart();
        return m_controlVisual;
    }

    mutable int m_controlTarget = -1;
    mutable QElapsedTimer m_controlTimer;
    mutable int m_controlVisual = -1;
    mutable QElapsedTimer m_visualTimer;
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
