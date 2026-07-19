#include <dlfcn.h>
#include <glib-object.h>
#include <glib.h>
#include <gmodule.h>

typedef gpointer (*GtkSettingsGetDefault)(void);
typedef gpointer (*GdkScreenGetDefault)(void);
typedef void (*GtkStyleContextResetWidgets)(gpointer screen);
typedef gpointer (*GtkCssProviderNew)(void);
typedef gboolean (*GtkCssProviderLoadFromPath)(gpointer provider,
        const gchar *path, GError **error);
typedef void (*GtkStyleContextAddProviderForScreen)(gpointer screen,
        gpointer provider, guint priority);
typedef void (*GtkStyleContextRemoveProviderForScreen)(gpointer screen,
        gpointer provider);

static gchar *settings_path;
static gchar *active_theme;
static gchar *active_font;
static gchar *active_css;
static gboolean active_dark;
static gboolean have_active_settings;
static gpointer active_css_provider;

static void write_diagnostic(const gchar *status)
{
    const gchar *cache = g_getenv("XDG_CACHE_HOME");
    if (cache == NULL || !g_path_is_absolute(cache)) return;
    gchar *path = g_build_filename(cache, "archphene-gtk-settings.log", NULL);
    g_file_set_contents(path, status, -1, NULL);
    g_free(path);
}

static GtkSettingsGetDefault resolve_settings_get_default(void)
{
    union {
        gpointer object;
        GtkSettingsGetDefault function;
    } symbol = {dlsym(RTLD_DEFAULT, "gtk_settings_get_default")};
    return symbol.function;
}

static gpointer default_screen(void)
{
    union {
        gpointer object;
        GdkScreenGetDefault function;
    } screen_symbol = {dlsym(RTLD_DEFAULT, "gdk_screen_get_default")};
    return screen_symbol.function == NULL ? NULL : screen_symbol.function();
}

static void reload_css_provider(gpointer screen, const gchar *css_path)
{
    if (screen == NULL || css_path == NULL) return;
    union {
        gpointer object;
        GtkCssProviderNew function;
    } new_symbol = {dlsym(RTLD_DEFAULT, "gtk_css_provider_new")};
    union {
        gpointer object;
        GtkCssProviderLoadFromPath function;
    } load_symbol = {dlsym(RTLD_DEFAULT, "gtk_css_provider_load_from_path")};
    union {
        gpointer object;
        GtkStyleContextAddProviderForScreen function;
    } add_symbol = {dlsym(RTLD_DEFAULT,
            "gtk_style_context_add_provider_for_screen")};
    union {
        gpointer object;
        GtkStyleContextRemoveProviderForScreen function;
    } remove_symbol = {dlsym(RTLD_DEFAULT,
            "gtk_style_context_remove_provider_for_screen")};
    if (new_symbol.function == NULL || load_symbol.function == NULL
            || add_symbol.function == NULL || remove_symbol.function == NULL) return;

    if (active_css_provider != NULL) {
        remove_symbol.function(screen, active_css_provider);
        g_object_unref(active_css_provider);
    }
    active_css_provider = new_symbol.function();
    GError *error = NULL;
    add_symbol.function(screen, active_css_provider, 801);
    if (!load_symbol.function(active_css_provider, css_path, &error) && error != NULL) {
        gchar *status = g_strdup_printf("CSS reload failed: %s\n", error->message);
        write_diagnostic(status);
        g_free(status);
        g_error_free(error);
    }
}

static void reset_widgets(gpointer screen)
{
    union {
        gpointer object;
        GtkStyleContextResetWidgets function;
    } reset_symbol = {dlsym(RTLD_DEFAULT, "gtk_style_context_reset_widgets")};
    if (screen != NULL && reset_symbol.function != NULL) reset_symbol.function(screen);
}

static gboolean refresh_settings(gpointer unused)
{
    (void)unused;
    if (settings_path == NULL) return G_SOURCE_CONTINUE;

    GKeyFile *file = g_key_file_new();
    if (!g_key_file_load_from_file(file, settings_path, G_KEY_FILE_NONE, NULL)) {
        g_key_file_unref(file);
        return G_SOURCE_CONTINUE;
    }
    gchar *theme = g_key_file_get_string(file, "Settings", "gtk-theme-name", NULL);
    gchar *font = g_key_file_get_string(file, "Settings", "gtk-font-name", NULL);
    gboolean dark = g_key_file_get_boolean(
            file, "Settings", "gtk-application-prefer-dark-theme", NULL);
    g_key_file_unref(file);
    if (theme == NULL || font == NULL) {
        g_free(theme);
        g_free(font);
        return G_SOURCE_CONTINUE;
    }
    gchar *directory = g_path_get_dirname(settings_path);
    gchar *css_path = g_build_filename(directory, "gtk.css", NULL);
    gchar *css = NULL;
    if (!g_file_get_contents(css_path, &css, NULL, NULL)) {
        g_free(theme);
        g_free(font);
        g_free(css_path);
        g_free(directory);
        return G_SOURCE_CONTINUE;
    }
    if (have_active_settings && active_dark == dark
            && g_strcmp0(active_theme, theme) == 0
            && g_strcmp0(active_font, font) == 0
            && g_strcmp0(active_css, css) == 0) {
        g_free(theme);
        g_free(font);
        g_free(css);
        g_free(css_path);
        g_free(directory);
        return G_SOURCE_CONTINUE;
    }

    GtkSettingsGetDefault get_default = resolve_settings_get_default();
    gpointer settings = get_default == NULL ? NULL : get_default();
    if (settings == NULL) {
        if (!have_active_settings) write_diagnostic(
                get_default == NULL ? "gtk_settings_get_default unresolved\n"
                                    : "GtkSettings unavailable\n");
        g_free(theme);
        g_free(font);
        g_free(css);
        g_free(css_path);
        g_free(directory);
        return G_SOURCE_CONTINUE;
    }
    g_object_set(settings,
            "gtk-theme-name", theme,
            "gtk-application-prefer-dark-theme", dark,
            "gtk-font-name", font,
            NULL);
    gpointer screen = default_screen();
    reload_css_provider(screen, css_path);
    reset_widgets(screen);
    g_free(active_theme);
    g_free(active_font);
    g_free(active_css);
    active_theme = theme;
    active_font = font;
    active_css = css;
    active_dark = dark;
    have_active_settings = TRUE;
    gchar *status = g_strdup_printf("applied theme=%s dark=%s font=%s\n",
            theme, dark ? "true" : "false", font);
    write_diagnostic(status);
    g_free(status);
    g_free(css_path);
    g_free(directory);
    return G_SOURCE_CONTINUE;
}

G_MODULE_EXPORT void gtk_module_init(gint *argc, gchar ***argv)
{
    (void)argc;
    (void)argv;
    const gchar *configured = g_getenv("ARCHPHENE_GTK_SETTINGS_FILE");
    if (configured == NULL || !g_path_is_absolute(configured)) return;
    settings_path = g_strdup(configured);
    write_diagnostic("initialized\n");
    refresh_settings(NULL);
    g_timeout_add(250, refresh_settings, NULL);
}
