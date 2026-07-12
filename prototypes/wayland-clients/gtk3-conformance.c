#include <gtk/gtk.h>
#include <stdio.h>

static gboolean log_button_event(GtkWidget *widget, GdkEventButton *event, gpointer label)
{
    GdkDevice *source = gdk_event_get_source_device((GdkEvent *) event);
    g_print("event=%s type=%u button=%u state=%u x=%.2f y=%.2f root=%.2f,%.2f time=%u source=%d\n",
            (const char *) label, event->type, event->button,
            event->state, event->x, event->y, event->x_root, event->y_root,
            event->time, source ? gdk_device_get_source(source) : -1);
    fflush(stdout);
    return FALSE;
}

static void log_pressed(GtkButton *button, gpointer label)
{
    g_print("signal=pressed label=%s sensitive=%d\n", (const char *) label,
            gtk_widget_is_sensitive(GTK_WIDGET(button)));
    fflush(stdout);
}

static void log_released(GtkButton *button, gpointer label)
{
    g_print("signal=released label=%s sensitive=%d\n", (const char *) label,
            gtk_widget_is_sensitive(GTK_WIDGET(button)));
    fflush(stdout);
}

static void log_enter(GtkButton *button, gpointer label)
{
    g_print("signal=enter label=%s\n", (const char *) label);
    fflush(stdout);
}

static void log_leave(GtkButton *button, gpointer label)
{
    g_print("signal=leave label=%s\n", (const char *) label);
    fflush(stdout);
}

static void log_clicked(GtkButton *button, gpointer label)
{
    g_print("clicked=%s active=%d\n", (const char *) label,
            GTK_IS_TOGGLE_BUTTON(button)
                ? gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(button)) : -1);
    fflush(stdout);
}

static void log_row_activated(GtkListBox *box, GtkListBoxRow *row, gpointer unused)
{
    g_print("row-activated=%d\n", gtk_list_box_row_get_index(row));
    fflush(stdout);
}

static void show_dialog(GtkButton *button, gpointer parent)
{
    GtkWidget *dialog = gtk_dialog_new_with_buttons(
            "Conformance Dialog", GTK_WINDOW(parent), GTK_DIALOG_MODAL,
            "Cancel", GTK_RESPONSE_CANCEL, "Accept", GTK_RESPONSE_ACCEPT, NULL);
    GtkWidget *content = gtk_dialog_get_content_area(GTK_DIALOG(dialog));
    GtkWidget *inner = gtk_button_new_with_label("Inner Dialog Button");
    g_signal_connect(inner, "clicked", G_CALLBACK(log_clicked), "dialog-inner");
    g_signal_connect(inner, "button-press-event", G_CALLBACK(log_button_event), "dialog-inner-press");
    g_signal_connect(inner, "button-release-event", G_CALLBACK(log_button_event), "dialog-inner-release");
    gtk_container_add(GTK_CONTAINER(content), inner);
    gtk_widget_show_all(dialog);
    int response = gtk_dialog_run(GTK_DIALOG(dialog));
    g_print("dialog-response=%d\n", response);
    fflush(stdout);
    gtk_widget_destroy(dialog);
}

static GtkWidget *make_button(const char *label, gboolean toggle)
{
    GtkWidget *button = toggle ? gtk_toggle_button_new_with_label(label)
                               : gtk_button_new_with_label(label);
    g_signal_connect(button, "clicked", G_CALLBACK(log_clicked), (gpointer) label);
    g_signal_connect(button, "pressed", G_CALLBACK(log_pressed), (gpointer) label);
    g_signal_connect(button, "released", G_CALLBACK(log_released), (gpointer) label);
    g_signal_connect(button, "enter", G_CALLBACK(log_enter), (gpointer) label);
    g_signal_connect(button, "leave", G_CALLBACK(log_leave), (gpointer) label);
    g_signal_connect(button, "button-press-event", G_CALLBACK(log_button_event), (gpointer) label);
    g_signal_connect(button, "button-release-event", G_CALLBACK(log_button_event), (gpointer) label);
    return button;
}

int main(int argc, char **argv)
{
    gtk_init(&argc, &argv);

    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(window), "GTK Wayland Conformance");
    gtk_window_set_default_size(GTK_WINDOW(window), 550, 700);
    g_signal_connect(window, "destroy", G_CALLBACK(gtk_main_quit), NULL);

    GtkWidget *layout = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_container_set_border_width(GTK_CONTAINER(layout), 16);
    gtk_container_add(GTK_CONTAINER(window), layout);

    GtkWidget *header = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    gtk_box_pack_start(GTK_BOX(layout), header, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(header), make_button("Cancel", FALSE), TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(header), make_button("Search", TRUE), TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(header), make_button("Open", FALSE), TRUE, TRUE, 0);

    GtkWidget *list = gtk_list_box_new();
    for (int i = 0; i < 4; i++) {
        char text[32];
        snprintf(text, sizeof(text), "Navigation Row %d", i + 1);
        gtk_list_box_insert(GTK_LIST_BOX(list), gtk_label_new(text), -1);
    }
    g_signal_connect(list, "row-activated", G_CALLBACK(log_row_activated), NULL);
    gtk_box_pack_start(GTK_BOX(layout), list, TRUE, TRUE, 0);

    GtkWidget *entry = gtk_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(entry), "Keyboard and IME input");
    gtk_box_pack_start(GTK_BOX(layout), entry, FALSE, FALSE, 0);

    GtkWidget *dialog_button = make_button("Open Child Dialog", FALSE);
    g_signal_connect(dialog_button, "clicked", G_CALLBACK(show_dialog), window);
    gtk_box_pack_start(GTK_BOX(layout), dialog_button, FALSE, FALSE, 0);

    gtk_widget_show_all(window);
    gtk_main();
    return 0;
}
