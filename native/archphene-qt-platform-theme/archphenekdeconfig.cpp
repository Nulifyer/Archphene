#include <KSharedConfig>
#include <QCoreApplication>
#include <QString>
#include <QVariant>

extern "C" Q_DECL_EXPORT bool archphene_reparse_kde_config()
{
    QCoreApplication *application = QCoreApplication::instance();
    if (application == nullptr) {
        return false;
    }
    KSharedConfig::openConfig()->reparseConfiguration();

    return true;
}