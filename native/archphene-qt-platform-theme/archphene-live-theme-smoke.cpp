#include <QAction>
#include <QApplication>
#include <QColor>
#include <QDir>
#include <QEvent>
#include <QFile>
#include <QFileInfo>
#include <QImage>
#include <QKeySequence>
#include <QMenu>
#include <QPainter>
#include <QPalette>
#include <QSaveFile>
#include <QStyle>
#include <QTimer>

#include <cstdio>

namespace {

bool writeScheme(const QString &path, bool dark)
{
    QSaveFile file(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Text)) {
        return false;
    }
    const QByteArray scheme = dark
            ? "[General]\nColorScheme=ArchpheneDark\n"
              "[Colors:Window]\nBackgroundNormal=35,38,41\nForegroundNormal=239,240,241\n"
              "[Colors:View]\nBackgroundNormal=27,30,32\nForegroundNormal=239,240,241\n"
              "[Colors:Button]\nBackgroundNormal=49,54,59\nForegroundNormal=239,240,241\n"
              "[Colors:Selection]\nBackgroundNormal=86,188,236\nForegroundNormal=17,20,23\n"
            : "[General]\nColorScheme=ArchpheneLight\n"
              "[Colors:Window]\nBackgroundNormal=239,240,241\nForegroundNormal=35,38,41\n"
              "[Colors:View]\nBackgroundNormal=255,255,255\nForegroundNormal=35,38,41\n"
              "[Colors:Button]\nBackgroundNormal=239,240,241\nForegroundNormal=35,38,41\n"
              "[Colors:Selection]\nBackgroundNormal=23,147,209\nForegroundNormal=255,255,255\n";
    return file.write(scheme) == scheme.size() && file.commit();
}

class PaletteObserver final : public QObject
{
public:
    bool eventFilter(QObject *object, QEvent *event) override
    {
        if (object == qApp && event->type() == QEvent::ApplicationPaletteChange) {
            ++changes;
        }
        return QObject::eventFilter(object, event);
    }

    int changes = 0;
};

QImage renderMenu(QApplication &application, int width, bool withShortcut)
{
    QMenu menu;
    menu.setAttribute(Qt::WA_DontShowOnScreen);
    QAction *action = menu.addAction(
            QStringLiteral("Configure Keyboard Shortcuts..."));
    if (withShortcut) {
        action->setShortcut(QKeySequence(QStringLiteral("Ctrl+Alt+,")));
    }
    menu.ensurePolished();
    menu.setFixedSize(width, qMax(64, menu.sizeHint().height()));
    menu.setActiveAction(action);
    menu.show();
    application.processEvents();

    QImage image(menu.size(), QImage::Format_ARGB32_Premultiplied);
    image.fill(Qt::transparent);
    QPainter painter(&image);
    menu.render(&painter);
    return image;
}

bool menuFitWorks(QApplication &application)
{
    QFont font = application.font();
    font.setPointSize(16);
    application.setFont(font);

    if (renderMenu(application, 360, true)
            != renderMenu(application, 360, false)) {
        std::fputs("constrained menu did not prioritize the action label\n", stderr);
        return false;
    }
    if (renderMenu(application, 720, true)
            == renderMenu(application, 720, false)) {
        std::fputs("wide menu unexpectedly discarded its shortcut\n", stderr);
        return false;
    }
    return true;
}

} // namespace

int main(int argc, char **argv)
{
    const QString configHome = qEnvironmentVariable("XDG_CONFIG_HOME");
    if (configHome.isEmpty() || !QDir().mkpath(configHome)) {
        std::fputs("XDG_CONFIG_HOME is missing or cannot be created\n", stderr);
        return 2;
    }
    const QString configPath = QDir(configHome).filePath(QStringLiteral("kdeglobals"));
    if (!writeScheme(configPath, false)) {
        std::fputs("could not write initial light scheme\n", stderr);
        return 3;
    }

    QApplication application(argc, argv);
    if (!menuFitWorks(application)) {
        return 8;
    }
    PaletteObserver observer;
    application.installEventFilter(&observer);

    const QColor lightWindow(239, 240, 241);
    const QColor darkWindow(35, 38, 41);
    QTimer::singleShot(0, [&]() {
        if (application.palette().color(QPalette::Window) != lightWindow) {
            std::fputs("production platform theme did not load the light palette\n", stderr);
            application.exit(4);
            return;
        }
        if (!writeScheme(configPath, true)) {
            std::fputs("could not atomically publish the dark scheme\n", stderr);
            application.exit(5);
        }
    });

    int attempts = 0;
    QTimer check;
    check.setInterval(25);
    QObject::connect(&check, &QTimer::timeout, [&]() {
        ++attempts;
        if (application.palette().color(QPalette::Window) == darkWindow) {
            if (observer.changes == 0) {
                std::fputs("palette changed without an application notification\n", stderr);
                application.exit(6);
                return;
            }
            std::printf("Qt live appearance passed after %d ms with %d palette notification(s)\n",
                    attempts * check.interval(), observer.changes);
            application.exit(0);
            return;
        }
        if (attempts >= 80) {
            std::fputs("timed out waiting for the event-driven dark palette\n", stderr);
            application.exit(7);
        }
    });
    check.start();
    return application.exec();
}
