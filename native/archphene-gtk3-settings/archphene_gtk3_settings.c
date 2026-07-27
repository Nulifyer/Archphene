#define _GNU_SOURCE

#include <dlfcn.h>
#include <glib-object.h>
#include <glib.h>
#include <gmodule.h>

typedef gpointer (*GtkSettingsGetDefault)(void);
typedef gpointer (*GdkScreenGetDefault)(void);
typedef gpointer (*GdkDisplayGetDefault)(void);
typedef void (*GtkStyleContextResetWidgets)(gpointer screen);
typedef gpointer (*GtkCssProviderNew)(void);
typedef gboolean (*Gtk3CssProviderLoadFromPath)(gpointer provider,
        const gchar *path, GError **error);
typedef void (*Gtk4CssProviderLoadFromPath)(gpointer provider, const gchar *path);
typedef void (*GtkStyleContextAddProviderForScreen)(gpointer screen,
        gpointer provider, guint priority);
typedef void (*GtkStyleContextRemoveProviderForScreen)(gpointer screen,
        gpointer provider);
typedef void (*GtkStyleContextAddProviderForDisplay)(gpointer display,
        gpointer provider, guint priority);
typedef void (*GtkStyleContextRemoveProviderForDisplay)(gpointer display,
        gpointer provider);
typedef gpointer (*AdwStyleManagerGetDefault)(void);
typedef void (*AdwStyleManagerSetColorScheme)(gpointer manager, gint scheme);
typedef gint (*GtkDialogRun)(gpointer dialog);
typedef GType (*GtkFileChooserGetType)(void);
typedef gint (*GtkFileChooserGetAction)(gpointer chooser);
typedef gchar *(*GtkFileChooserGetCurrentName)(gpointer chooser);
typedef gpointer (*GtkFileChooserNativeNew)(const gchar *title, gpointer parent,
        gint action, const gchar *accept_label, const gchar *cancel_label);
typedef gpointer (*GtkWindowGetTransientFor)(gpointer window);
typedef const gchar *(*GtkWindowGetTitle)(gpointer window);
typedef void (*GtkFileChooserSetCurrentName)(gpointer chooser, const gchar *name);
typedef void (*GtkFileChooserSetOverwriteConfirmation)(
        gpointer chooser, gboolean enabled);
typedef gboolean (*GtkFileChooserGetOverwriteConfirmation)(gpointer chooser);
typedef gint (*GtkNativeDialogRun)(gpointer dialog);
typedef gpointer (*GtkFileChooserGetFile)(gpointer chooser);
typedef gboolean (*GtkFileChooserSetFile)(
        gpointer chooser, gpointer file, GError **error);

static gchar *settings_path;
static gchar *active_theme;
static gchar *active_font;
static gchar *active_css;
static gboolean active_dark;
static gboolean have_active_settings;
static gpointer active_css_provider;
static gboolean refresh_started;

enum {
    ARCHPHENE_GTK_FILE_CHOOSER_ACTION_SAVE = 1,
    ARCHPHENE_GTK_RESPONSE_ACCEPT = -3,
    ARCHPHENE_GTK_RESPONSE_OK = -5,
    ARCHPHENE_GTK_RESPONSE_CANCEL = -6,
};

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
    if (css_path == NULL) return;
    union {
        gpointer object;
        GtkCssProviderNew function;
    } new_symbol = {dlsym(RTLD_DEFAULT, "gtk_css_provider_new")};
    union {
        gpointer object;
        GdkDisplayGetDefault function;
    } display_symbol = {dlsym(RTLD_DEFAULT, "gdk_display_get_default")};
    union {
        gpointer object;
        GtkStyleContextAddProviderForDisplay function;
    } add_display_symbol = {dlsym(RTLD_DEFAULT,
            "gtk_style_context_add_provider_for_display")};
    union {
        gpointer object;
        GtkStyleContextRemoveProviderForDisplay function;
    } remove_display_symbol = {dlsym(RTLD_DEFAULT,
            "gtk_style_context_remove_provider_for_display")};
    if (new_symbol.function != NULL && display_symbol.function != NULL
            && add_display_symbol.function != NULL
            && remove_display_symbol.function != NULL) {
        gpointer display = display_symbol.function();
        if (display == NULL) return;
        if (active_css_provider != NULL) {
            remove_display_symbol.function(display, active_css_provider);
            g_object_unref(active_css_provider);
        }
        active_css_provider = new_symbol.function();
        union {
            gpointer object;
            Gtk4CssProviderLoadFromPath function;
        } load_symbol = {dlsym(RTLD_DEFAULT, "gtk_css_provider_load_from_path")};
        if (load_symbol.function == NULL) return;
        load_symbol.function(active_css_provider, css_path);
        add_display_symbol.function(display, active_css_provider, 801);
        return;
    }
    if (screen == NULL) return;
    union {
        gpointer object;
        Gtk3CssProviderLoadFromPath function;
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

static void update_libadwaita(gboolean dark)
{
    union {
        gpointer object;
        AdwStyleManagerGetDefault function;
    } get_symbol = {dlsym(RTLD_DEFAULT, "adw_style_manager_get_default")};
    union {
        gpointer object;
        AdwStyleManagerSetColorScheme function;
    } set_symbol = {dlsym(RTLD_DEFAULT, "adw_style_manager_set_color_scheme")};
    if (get_symbol.function == NULL || set_symbol.function == NULL) return;
    gpointer manager = get_symbol.function();
    if (manager != NULL) set_symbol.function(manager, dark ? 4 : 1);
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
    update_libadwaita(dark);
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

static void start_refresh(void)
{
    if (refresh_started) return;
    const gchar *configured = g_getenv("ARCHPHENE_GTK_SETTINGS_FILE");
    if (configured == NULL || !g_path_is_absolute(configured)) return;
    refresh_started = TRUE;
    settings_path = g_strdup(configured);
    write_diagnostic("initialized\n");
    refresh_settings(NULL);
    g_timeout_add(250, refresh_settings, NULL);
}

/*
 * GtkFileChooserDialog predates the portal-aware GtkFileChooserNative API and
 * remains common in otherwise unmodified GTK3 applications. Intercept only its
 * synchronous SaveFile path and delegate to GtkFileChooserNative. The native
 * object preserves GTK's portal protocol implementation while the original
 * dialog retains application-owned state such as encoding controls.
 */
G_MODULE_EXPORT gint gtk_dialog_run(gpointer dialog)
{
    union {
        gpointer object;
        GtkDialogRun function;
    } original = {dlsym(RTLD_NEXT, "gtk_dialog_run")};
    if (original.function == NULL) return ARCHPHENE_GTK_RESPONSE_CANCEL;
    if (g_strcmp0(g_getenv("ARCHPHENE_GTK_FILE_PORTAL"), "1") != 0
            || dialog == NULL) return original.function(dialog);

    union {
        gpointer object;
        GtkFileChooserGetType function;
    } get_type = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_get_type")};
    union {
        gpointer object;
        GtkFileChooserGetAction function;
    } get_action = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_get_action")};
    union {
        gpointer object;
        GtkFileChooserGetCurrentName function;
    } get_name = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_get_current_name")};
    union {
        gpointer object;
        GtkFileChooserNativeNew function;
    } native_new = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_native_new")};
    union {
        gpointer object;
        GtkWindowGetTransientFor function;
    } get_parent = {dlsym(RTLD_DEFAULT, "gtk_window_get_transient_for")};
    union {
        gpointer object;
        GtkWindowGetTitle function;
    } get_title = {dlsym(RTLD_DEFAULT, "gtk_window_get_title")};
    union {
        gpointer object;
        GtkFileChooserSetCurrentName function;
    } set_name = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_set_current_name")};
    union {
        gpointer object;
        GtkFileChooserGetOverwriteConfirmation function;
    } get_overwrite = {
            dlsym(RTLD_DEFAULT, "gtk_file_chooser_get_do_overwrite_confirmation")};
    union {
        gpointer object;
        GtkFileChooserSetOverwriteConfirmation function;
    } set_overwrite = {
            dlsym(RTLD_DEFAULT, "gtk_file_chooser_set_do_overwrite_confirmation")};
    union {
        gpointer object;
        GtkNativeDialogRun function;
    } native_run = {dlsym(RTLD_DEFAULT, "gtk_native_dialog_run")};
    union {
        gpointer object;
        GtkFileChooserGetFile function;
    } get_file = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_get_file")};
    union {
        gpointer object;
        GtkFileChooserSetFile function;
    } set_file = {dlsym(RTLD_DEFAULT, "gtk_file_chooser_set_file")};
    if (get_type.function == NULL || get_action.function == NULL
            || get_name.function == NULL || native_new.function == NULL
            || get_parent.function == NULL || get_title.function == NULL
            || set_name.function == NULL || get_overwrite.function == NULL
            || set_overwrite.function == NULL || native_run.function == NULL
            || get_file.function == NULL || set_file.function == NULL
            || !g_type_check_instance_is_a(
                    (GTypeInstance *)dialog, get_type.function())
            || get_action.function(dialog)
                    != ARCHPHENE_GTK_FILE_CHOOSER_ACTION_SAVE) {
        return original.function(dialog);
    }

    gchar *name = get_name.function(dialog);
    const gchar *title = get_title.function(dialog);
    gpointer native = native_new.function(
            title == NULL || title[0] == '\0' ? "Save Linux document" : title,
            get_parent.function(dialog),
            ARCHPHENE_GTK_FILE_CHOOSER_ACTION_SAVE, "_Save", "_Cancel");
    if (native == NULL) {
        g_free(name);
        return original.function(dialog);
    }
    if (name != NULL && name[0] != '\0') set_name.function(native, name);
    set_overwrite.function(native, get_overwrite.function(dialog));
    g_free(name);

    write_diagnostic("Delegating GTK3 SaveFile to the Android portal\n");
    gint response = native_run.function(native);
    gchar *response_status = g_strdup_printf(
            "File portal native response: %d\n", response);
    write_diagnostic(response_status);
    g_free(response_status);
    if (response != ARCHPHENE_GTK_RESPONSE_ACCEPT
            && response != ARCHPHENE_GTK_RESPONSE_OK) {
        g_object_unref(native);
        return response;
    }
    gpointer file = get_file.function(native);
    if (file == NULL) {
        g_object_unref(native);
        return ARCHPHENE_GTK_RESPONSE_CANCEL;
    }
    GError *error = NULL;
    gboolean selected = set_file.function(dialog, file, &error);
    g_object_unref(file);
    g_object_unref(native);
    if (!selected) {
        if (error != NULL) {
            gchar *status = g_strdup_printf(
                    "File portal could not apply selection: %s\n", error->message);
            write_diagnostic(status);
            g_free(status);
            g_error_free(error);
        }
        return ARCHPHENE_GTK_RESPONSE_CANCEL;
    }
    /*
     * GtkFileChooserDialog applies a GFile selection asynchronously while its
     * normal dialog loop is running. The portal has already ended that loop,
     * so drain the bounded GLib context until the chooser's public getter can
     * observe the selection. Returning ACCEPT before this point makes callers
     * such as editors see a null destination and silently skip their write.
     */
    gboolean visible = FALSE;
    for (guint attempt = 0; attempt < 2000; attempt++) {
        gpointer applied = get_file.function(dialog);
        if (applied != NULL) {
            g_object_unref(applied);
            visible = TRUE;
            break;
        }
        while (g_main_context_iteration(NULL, FALSE)) {}
        g_usleep(1000);
    }
    if (!visible) {
        write_diagnostic("File portal selection did not settle\n");
        return ARCHPHENE_GTK_RESPONSE_CANCEL;
    }
    write_diagnostic("File portal SaveFile selection applied\n");
    return ARCHPHENE_GTK_RESPONSE_ACCEPT;
}

__attribute__((constructor)) static void preload_init(void)
{
    if (g_strcmp0(g_getenv("ARCHPHENE_GTK_SETTINGS_PRELOAD"), "1") == 0) {
        start_refresh();
    }
}

G_MODULE_EXPORT void gtk_module_init(gint *argc, gchar ***argv)
{
    (void)argc;
    (void)argv;
    start_refresh();
}
