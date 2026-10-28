package com.atakmap.android.quicksearch.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Plugin entry point. Adds a "QuickSearch" toolbar button that opens the
 * search pane. Tapping the button again while the pane is open closes it.
 */
public class QuickSearchPlugin implements IPlugin {

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;

    private Pane pane;
    private SearchPane searchPane;

    public QuickSearchPlugin(IServiceController serviceController) {
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        pluginContext = ctxProvider != null
                ? ctxProvider.getPluginContext()
                : null;
        if (pluginContext != null)
            pluginContext.setTheme(R.style.ATAKPluginTheme);

        uiService = serviceController.getService(IHostUIService.class);

        if (pluginContext == null) {
            toolbarItem = null;
            return;
        }

        // the identifier must stay stable or ATAK loses the button's
        // position whenever the user rearranges the toolbar
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources()
                                .getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        togglePane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService != null && toolbarItem != null)
            uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        if (pane != null && uiService.isPaneVisible(pane))
            uiService.closePane(pane);
        if (toolbarItem != null)
            uiService.removeToolbarItem(toolbarItem);
        if (searchPane != null)
            searchPane.dispose();
        searchPane = null;
        pane = null;
    }

    private void togglePane() {
        if (uiService == null)
            return;

        if (pane != null && uiService.isPaneVisible(pane)) {
            uiService.closePane(pane);
            return;
        }

        if (pane == null) {
            MapView mapView = MapView.getMapView();
            if (mapView == null)
                return;
            searchPane = new SearchPane(pluginContext, mapView);
            pane = new PaneBuilder(searchPane.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.4D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        uiService.showPane(pane, new IHostUIService.IPaneLifecycleListener() {
            @Override
            public void onPaneVisible(boolean visible) {
                if (searchPane == null)
                    return;
                if (visible)
                    searchPane.onShown();
                else
                    searchPane.onHidden();
            }

            @Override
            public void onPaneClose() {
                if (searchPane != null)
                    searchPane.onHidden();
            }
        });

        // don't rely solely on the visibility callback to focus the box;
        // a second refresh is cheap
        final SearchPane sp = searchPane;
        MapView mv = MapView.getMapView();
        if (mv != null && sp != null) {
            mv.post(new Runnable() {
                @Override
                public void run() {
                    sp.onShown();
                }
            });
        }
    }
}
