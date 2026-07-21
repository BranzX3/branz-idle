package dev.branzx.idle.gui;

/**
 * Optional alternate presentation layer. Returning false lets the normal
 * Java inventory renderer open the menu.
 */
public interface MenuRenderer {
    boolean open(Menu menu);
}
