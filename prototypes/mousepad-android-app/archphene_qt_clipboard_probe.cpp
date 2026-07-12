#include <QApplication>
#include <QClipboard>
#include <QDebug>
#include <QLabel>
#include <QTimer>
#include <QTextStream>

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    QLabel window("Archphene clipboard probe");
    window.resize(480, 160);
    window.show();

    QString inbound;
    QTimer::singleShot(2500, [&inbound] {
        inbound = QApplication::clipboard()->text();
        qInfo().noquote() << "ARCHPHENE_INBOUND=" + inbound;
    });
    QTimer::singleShot(3500, [] {
        QApplication::clipboard()->setText("ARCHPHENE_CLIPBOARD_PROBE");
    });
    QTimer::singleShot(4500, [&inbound] {
        QTextStream(stdout) << "ARCHPHENE_INBOUND_FINAL=" << inbound << Qt::endl;
    });
    QTimer::singleShot(5000, &app, &QCoreApplication::quit);
    return app.exec();
}