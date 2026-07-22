#include <QApplication>
#include <QColor>
#include <QDir>
#include <QEvent>
#include <QFile>
#include <QFont>
#include <QGuiApplication>
#include <QLibrary>
#include <QPalette>
#include <QSettings>
#include <QStringList>
#include <QTimer>
#include <QVariant>
#include <QWidget>
#include <qpa/qplatformtheme.h>
#include <qpa/qplatformthemeplugin.h>
#include <qpa/qwindowsysteminterface.h>
#include <memory>

namespace {

QColor readColor(QSettings &settings, const QString &group, const QString &key,
        const QColor &fallback)
{
    const QVariant value = settings.value(group + QLatin1Char('/') + key);
    QString encoded = value.toString();
    if (encoded.isEmpty()) {
        encoded = value.toStringList().join(QLatin1Char(','));
    }
    const QStringList parts = encoded.split(QLatin1Char(','));
    if (parts.size() != 3) {
        return fallback;
    }
    bool redOk = false;
    bool greenOk = false;
    bool blueOk = false;
    const int red = parts[0].trimmed().toInt(&redOk);
    const int green = parts[1].trimmed().toInt(&greenOk);
    const int blue = parts[2].trimmed().toInt(&blueOk);
    if (!redOk || !greenOk || !blueOk
            || red < 0 || red > 255 || green < 0 || green > 255
            || blue < 0 || blue > 255) {
        return fallback;
    }
    return QColor(red, green, blue);
}

QColor blend(const QColor &foreground, const QColor &background, qreal amount)
{
    return QColor::fromRgbF(
            foreground.redF() * amount + background.redF() * (1.0 - amount),
            foreground.greenF() * amount + background.greenF() * (1.0 - amount),
            foreground.blueF() * amount + background.blueF() * (1.0 - amount));
}

void logKdeHelperError(const QString &message)
{
    QFile log(QDir::homePath() + QStringLiteral("/.cache/archphene-qt-theme.log"));
    if (log.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text)) {
        log.write(message.toUtf8());
        log.write("\n");
    }
}

void reparseKdeColorConfig()
{
    QLibrary helper(QDir(QDir::homePath()).filePath(
            QStringLiteral("../linux-runtime/lib/libarchphene_kde_config.so")));
    if (!helper.load()) {
        logKdeHelperError(helper.errorString());
        return;
    }
    using Reparse = bool (*)();
    Reparse reparse = reinterpret_cast<Reparse>(
            helper.resolve("archphene_reparse_kde_config"));
    if (reparse == nullptr) {
        logKdeHelperError(helper.errorString());
        return;
    }
    if (!reparse()) {
        logKdeHelperError(QStringLiteral("KF6Config helper rejected the reparse request"));
    }
}

class ArchphenePlatformTheme final : public QPlatformTheme
{
public:
    ArchphenePlatformTheme()
        : ArchphenePlatformTheme(true)
    {
    }

private:
    explicit ArchphenePlatformTheme(bool watch)
    {
        const QString configHome = qEnvironmentVariable("XDG_CONFIG_HOME",
                QDir::homePath() + QStringLiteral("/.config"));
        m_configPath = configHome + QStringLiteral("/kdeglobals");
        QSettings settings(m_configPath, QSettings::IniFormat);
        settings.sync();
        const QString configuredScheme = settings.value(
                QStringLiteral("General/ColorScheme")).toString();
        const bool dark = configuredScheme.endsWith(
                QStringLiteral("Dark"), Qt::CaseInsensitive)
                || (configuredScheme.isEmpty()
                    && qEnvironmentVariable("ARCHPHENE_COLOR_SCHEME")
                            == QLatin1String("dark"));

        const QColor fallbackWindow = dark ? QColor(35, 38, 41) : QColor(239, 240, 241);
        const QColor fallbackView = dark ? QColor(27, 30, 32) : QColor(255, 255, 255);
        const QColor fallbackButton = dark ? QColor(49, 54, 59) : QColor(239, 240, 241);
        const QColor fallbackText = dark ? QColor(239, 240, 241) : QColor(35, 38, 41);
        const QColor fallbackAccent = dark ? QColor(86, 188, 236) : QColor(23, 147, 209);

        const QColor window = readColor(settings, QStringLiteral("Colors:Window"),
                QStringLiteral("BackgroundNormal"), fallbackWindow);
        const QColor windowText = readColor(settings, QStringLiteral("Colors:Window"),
                QStringLiteral("ForegroundNormal"), fallbackText);
        const QColor view = readColor(settings, QStringLiteral("Colors:View"),
                QStringLiteral("BackgroundNormal"), fallbackView);
        const QColor alternate = readColor(settings, QStringLiteral("Colors:View"),
                QStringLiteral("BackgroundAlternate"), fallbackWindow);
        const QColor text = readColor(settings, QStringLiteral("Colors:View"),
                QStringLiteral("ForegroundNormal"), fallbackText);
        const QColor link = readColor(settings, QStringLiteral("Colors:View"),
                QStringLiteral("ForegroundLink"), fallbackAccent);
        const QColor visited = readColor(settings, QStringLiteral("Colors:View"),
                QStringLiteral("ForegroundVisited"), link);
        const QColor button = readColor(settings, QStringLiteral("Colors:Button"),
                QStringLiteral("BackgroundNormal"), fallbackButton);
        const QColor buttonText = readColor(settings, QStringLiteral("Colors:Button"),
                QStringLiteral("ForegroundNormal"), fallbackText);
        const QColor highlight = readColor(settings, QStringLiteral("Colors:Selection"),
                QStringLiteral("BackgroundNormal"), fallbackAccent);
        const QColor highlightedText = readColor(settings, QStringLiteral("Colors:Selection"),
                QStringLiteral("ForegroundNormal"), dark ? QColor(17, 20, 23) : QColor(Qt::white));
        const QColor tooltip = readColor(settings, QStringLiteral("Colors:Tooltip"),
                QStringLiteral("BackgroundNormal"), fallbackButton);
        const QColor tooltipText = readColor(settings, QStringLiteral("Colors:Tooltip"),
                QStringLiteral("ForegroundNormal"), fallbackText);

        for (QPalette::ColorGroup group : {
                QPalette::Active, QPalette::Inactive, QPalette::Disabled}) {
            const bool disabled = group == QPalette::Disabled;
            const qreal textAmount = disabled ? 0.48 : group == QPalette::Inactive ? 0.75 : 1.0;
            const QColor groupWindowText = blend(windowText, window, textAmount);
            const QColor groupText = blend(text, view, textAmount);
            const QColor groupButtonText = blend(buttonText, button, textAmount);
            m_palette.setColor(group, QPalette::Window, window);
            m_palette.setColor(group, QPalette::WindowText, groupWindowText);
            m_palette.setColor(group, QPalette::Base, view);
            m_palette.setColor(group, QPalette::AlternateBase, alternate);
            m_palette.setColor(group, QPalette::Text, groupText);
            m_palette.setColor(group, QPalette::PlaceholderText, blend(text, view, 0.55));
            m_palette.setColor(group, QPalette::Button, button);
            m_palette.setColor(group, QPalette::ButtonText, groupButtonText);
            m_palette.setColor(group, QPalette::Light, button.lighter(150));
            m_palette.setColor(group, QPalette::Midlight, button.lighter(125));
            m_palette.setColor(group, QPalette::Mid, button.darker(125));
            m_palette.setColor(group, QPalette::Dark, button.darker(160));
            m_palette.setColor(group, QPalette::Shadow, button.darker(220));
            m_palette.setColor(group, QPalette::Highlight, highlight);
            m_palette.setColor(group, QPalette::HighlightedText, highlightedText);
            m_palette.setColor(group, QPalette::Link, link);
            m_palette.setColor(group, QPalette::LinkVisited, visited);
            m_palette.setColor(group, QPalette::ToolTipBase, tooltip);
            m_palette.setColor(group, QPalette::ToolTipText, tooltipText);
            m_palette.setColor(group, QPalette::BrightText, QColor(218, 68, 83));
            m_palette.setColor(group, QPalette::Accent, highlight);
        }

        bool pointSizeOk = false;
        int pointSize = qEnvironmentVariableIntValue(
                "ARCHPHENE_FONT_POINT_SIZE", &pointSizeOk);
        if (!pointSizeOk) {
            pointSize = 18;
        }
        pointSize = qBound(9, pointSize, 48);
        m_font.setFamilies({QStringLiteral("Noto Sans"), QStringLiteral("sans-serif")});
        m_font.setPointSize(pointSize);
        m_fixedFont.setFamilies(
                {QStringLiteral("Noto Sans Mono"), QStringLiteral("monospace")});
        m_fixedFont.setPointSize(pointSize);
        m_scheme = dark ? Qt::ColorScheme::Dark : Qt::ColorScheme::Light;

        if (watch) {
            logKdeHelperError(QStringLiteral(
                    "initialized path=%1 scheme=%2 window=%3 view=%4 button=%5 accent=%6")
                    .arg(m_configPath, dark ? QStringLiteral("dark")
                                            : QStringLiteral("light"),
                            window.name(QColor::HexRgb), view.name(QColor::HexRgb),
                            button.name(QColor::HexRgb), highlight.name(QColor::HexRgb)));
            m_pollTimer = std::make_unique<QTimer>();
            m_pollTimer->setInterval(500);
            QObject::connect(m_pollTimer.get(), &QTimer::timeout, [this]() {
                ArchphenePlatformTheme refreshed(false);
                const bool changed = m_scheme != refreshed.m_scheme
                        || m_palette != refreshed.m_palette;
                if (!m_forceRefresh && !changed) {
                    return;
                }
                m_forceRefresh = false;
                if (changed) {
                    m_palette = refreshed.m_palette;
                    m_font = refreshed.m_font;
                    m_fixedFont = refreshed.m_fixedFont;
                    m_scheme = refreshed.m_scheme;
                }
                if (qApp != nullptr) {
                    // Plasma reparses kdeglobals before ApplicationPaletteChange so
                    // KColorScheme-backed custom painting reads the new colors.
                    qApp->setProperty("KDE_COLOR_SCHEME_PATH", QString());
                    reparseKdeColorConfig();
                }
                if (qobject_cast<QApplication *>(QCoreApplication::instance()) != nullptr) {
                    QApplication::setPalette(m_palette);
                    if (qApp != nullptr) {
                        QEvent applicationChange(QEvent::ApplicationPaletteChange);
                        QCoreApplication::sendEvent(qApp, &applicationChange);
                    }
                    QWindowSystemInterface::handleThemeChange();
                    for (QWidget *widget : QApplication::topLevelWidgets()) {
                        QEvent widgetChange(QEvent::ApplicationPaletteChange);
                        QCoreApplication::sendEvent(widget, &widgetChange);
                        widget->update();
                    }
                } else if (qGuiApp != nullptr) {
                    QGuiApplication::setPalette(m_palette);
                    QWindowSystemInterface::handleThemeChange();
                }
            });
            QTimer::singleShot(0, QCoreApplication::instance(), [this]() {
                if (qobject_cast<QApplication *>(QCoreApplication::instance()) != nullptr) {
                    QApplication::setPalette(m_palette);
                    QWindowSystemInterface::handleThemeChange();
                } else if (qGuiApp != nullptr) {
                    QGuiApplication::setPalette(m_palette);
                    QWindowSystemInterface::handleThemeChange();
                }
                m_pollTimer->start();
            });
        }
    }

public:
    const QPalette *palette(Palette) const override
    {
        return &m_palette;
    }

    const QFont *font(Font type) const override
    {
        return type == FixedFont ? &m_fixedFont : &m_font;
    }

    Qt::ColorScheme colorScheme() const override
    {
        return m_scheme;
    }

    QVariant themeHint(ThemeHint hint) const override
    {
        switch (hint) {
        case StyleNames:
            return QStringList{QStringLiteral("archphene"), QStringLiteral("fusion")};
        case SystemIconThemeName:
            return QStringLiteral("breeze");
        case SystemIconFallbackThemeName:
            return QStringLiteral("hicolor");
        case WheelScrollLines:
            return 3;
        case UseFullScreenForPopupMenu:
            return false;
        case DialogButtonBoxButtonsHaveIcons:
            return false;
        case ShowShortcutsInContextMenus:
            return true;
        default:
            return QPlatformTheme::themeHint(hint);
        }
    }

private:
    QPalette m_palette;
    QFont m_font;
    QFont m_fixedFont;
    Qt::ColorScheme m_scheme = Qt::ColorScheme::Unknown;
    QString m_configPath;
    std::unique_ptr<QTimer> m_pollTimer;
    bool m_forceRefresh = true;
};

class ArchphenePlatformThemePlugin final : public QPlatformThemePlugin
{
    Q_OBJECT
    Q_PLUGIN_METADATA(
            IID QPlatformThemeFactoryInterface_iid FILE "archpheneplatformtheme.json")

public:
    QPlatformTheme *create(const QString &key, const QStringList &) override
    {
        if (key.compare(QLatin1String("archphene"), Qt::CaseInsensitive) != 0) {
            return nullptr;
        }
        return new ArchphenePlatformTheme;
    }
};

} // namespace

#include "archpheneplatformtheme.moc"
