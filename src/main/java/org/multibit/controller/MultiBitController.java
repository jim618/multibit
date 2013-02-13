/**
 * Copyright 2012 multibit.org
 *
 * Licensed under the MIT license (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.multibit.controller;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.multibit.ApplicationDataDirectoryLocator;
import org.multibit.file.FileHandler;
import org.multibit.message.MessageManager;
import org.multibit.model.MultiBitModel;
import org.multibit.model.PerWalletModelData;
import org.multibit.model.WalletBusyListener;
import org.multibit.network.MultiBitService;
import org.multibit.platform.listener.GenericAboutEvent;
import org.multibit.platform.listener.GenericAboutEventListener;
import org.multibit.platform.listener.GenericOpenURIEvent;
import org.multibit.platform.listener.GenericOpenURIEventListener;
import org.multibit.platform.listener.GenericPreferencesEvent;
import org.multibit.platform.listener.GenericPreferencesEventListener;
import org.multibit.platform.listener.GenericQuitEvent;
import org.multibit.platform.listener.GenericQuitEventListener;
import org.multibit.platform.listener.GenericQuitResponse;
import org.multibit.viewsystem.View;
import org.multibit.viewsystem.ViewSystem;
import org.multibit.viewsystem.swing.action.ExitAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.PeerEventListener;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletEventListener;
import com.google.bitcoin.uri.BitcoinURI;
import com.google.bitcoin.uri.BitcoinURIParseException;

/**
 * The MVC controller for MultiBit.
 * 
 * @author jim
 */
public class MultiBitController extends AbstractController<CoreController> implements WalletEventListener {

    public static final String ENCODED_SPACE_CHARACTER = "%20";
    private Logger log = LoggerFactory.getLogger(MultiBitController.class);

    /**
     * The WalletBusy listeners
     */
    private final Collection<WalletBusyListener> walletBusyListeners;
    
    private EventHandeler eventHandeler;
    
    /**
     * The bitcoinj network interface.
     */
    private MultiBitService multiBitService;
    /**
     * Class encapsulating File IO.
     */
    private final FileHandler fileHandler;
    
    /**
     * The listener handling Peer events.
     */
    private final PeerEventListener peerEventListener;
    
    /**
     * Multiple threads will write to this variable so require it to be volatile
     * to ensure that latest write is what gets read
     */
    private volatile URI rawBitcoinURI = null;
    
    /**
     * Used for testing only.
     */
    public MultiBitController(CoreController coreController) {
        super(coreController);

        this.walletBusyListeners = new ArrayList<WalletBusyListener>();
        this.fileHandler = new FileHandler(this);
        this.eventHandeler = new EventHandeler(this);
        this.peerEventListener = new MultiBitPeerEventListener(this);
        
        this.addEventHandler(this.getEventHandeler());
    }

    



    /**
     * Register a new WalletBusyListener.
     */
    public void registerWalletBusyListener(WalletBusyListener walletBusyListener) {
        walletBusyListeners.add(walletBusyListener);
    }
    
    /**
     * Clear the wallet busy listeners
     */
    public void clearWalletBusyListeners() {
        walletBusyListeners.clear();
    }
    
    /**
     * Log the number of wallet busy listeners
     */
    public void logNumberOfWalletBusyListeners() {
        log.debug("There are " + walletBusyListeners.size() + " walletBusyListeners.");
    }

    /**
     * Add a wallet to multibit from a filename.
     * 
     * @param walletFilename The wallet filename
     * 
     * @return The model data
     */
    public PerWalletModelData addWalletFromFilename(String walletFilename) throws IOException {
        PerWalletModelData perWalletModelDataToReturn = null;
        if (multiBitService != null) {
            perWalletModelDataToReturn = multiBitService.addWalletFromFilename(walletFilename);
        }
        return perWalletModelDataToReturn;
    }

    public void fireFilesHaveBeenChangedByAnotherProcess(PerWalletModelData perWalletModelData) {
        //log.debug("fireFilesHaveBeenChangedByAnotherProcess called");
        for (ViewSystem viewSystem : super.getViewSystem()) {
            viewSystem.fireFilesHaveBeenChangedByAnotherProcess(perWalletModelData);
        }

        fireDataChangedUpdateNow();
    }
       
    /**
     * Fire that a wallet has changed its busy state.
     */
    public void fireWalletBusyChange(boolean newWalletIsBusy) {
        //log.debug("fireWalletBusyChange called");
        for( Iterator<WalletBusyListener> it = walletBusyListeners.iterator(); it.hasNext();) {
            WalletBusyListener walletBusyListener = it.next();
            walletBusyListener.walletBusyChange(newWalletIsBusy);
        }
    }

    /**
     * Method called by downloadListener whenever a block is downloaded.
     */
    public void fireBlockDownloaded() {
        // log.debug("Fire blockdownloaded");
        for (ViewSystem viewSystem : super.getViewSystem()) {
            viewSystem.blockDownloaded();
        }
        
        // Mark all the wallets as dirty as their lastBlockSeenHeight will need changing.
        if (getModel() != null) {
            List<PerWalletModelData> perWalletModelDataList = getModel().getPerWalletModelDataList();
            if (perWalletModelDataList != null) {
                for (PerWalletModelData loopPerWalletModelData : perWalletModelDataList) {
                    loopPerWalletModelData.setDirty(true);
                }
            }
        }
    }

    @Override
    public void onCoinsReceived(Wallet wallet, Transaction transaction, BigInteger prevBalance, BigInteger newBalance) {
        //log.debug("onCoinsReceived called");
        for (ViewSystem viewSystem : super.getViewSystem()) {
            viewSystem.onCoinsReceived(wallet, transaction, prevBalance, newBalance);
        }
    }

    @Override
    public void onCoinsSent(Wallet wallet, Transaction transaction, BigInteger prevBalance, BigInteger newBalance) {
        //log.debug("onCoinsSent called");
        for (ViewSystem viewSystem : super.getViewSystem()) {
            viewSystem.onCoinsSent(wallet, transaction, prevBalance, newBalance);
        }
    }
    
    @Override
    public void onWalletChanged(Wallet wallet) {
        if (wallet == null) {
            return;
        }
        // log.debug("onWalletChanged called");
        final int walletIdentityHashCode = System.identityHashCode(wallet);
        for (PerWalletModelData loopPerWalletModelData : getModel().getPerWalletModelDataList()) {
            // Find the wallet object and mark as dirty.
            if (System.identityHashCode(loopPerWalletModelData.getWallet()) == walletIdentityHashCode) {
                loopPerWalletModelData.setDirty(true);
                break;
            }
        }

        fireDataChangedUpdateLater();
    }

    @Override
    public void onTransactionConfidenceChanged(Wallet wallet, Transaction transaction) {
        //log.debug("onTransactionConfidenceChanged called");
        for (ViewSystem viewSystem : super.getViewSystem()) {
            viewSystem.onTransactionConfidenceChanged(wallet, transaction);
        }
    }

    @Override
    public void onKeyAdded(ECKey ecKey) {
        log.debug("Key added : " + ecKey.toString());
    }

    @Override
    public void onReorganize(Wallet wallet) {
        log.debug("onReorganize called");
        List<PerWalletModelData> perWalletModelDataList = getModel().getPerWalletModelDataList();
        for (PerWalletModelData loopPerWalletModelData : perWalletModelDataList) {
            if (loopPerWalletModelData.getWallet().equals(wallet)) {
                loopPerWalletModelData.setDirty(true);
                log.debug("Marking wallet '" + loopPerWalletModelData.getWalletFilename() + "' as dirty.");
            }
        }
        for (ViewSystem viewSystem : super.getViewSystem()) {
            viewSystem.onReorganize(wallet);
        }
    }

    public MultiBitService getMultiBitService() {
        return multiBitService;
    }

    public void setMultiBitService(MultiBitService multiBitService) {
        this.multiBitService = multiBitService;
    }

    public FileHandler getFileHandler() {
        return fileHandler;
    }

    public synchronized void handleOpenURI() {
        log.debug("handleOpenURI called and rawBitcoinURI ='" + this.eventHandeler.rawBitcoinURI + "'");

        // get the open URI configuration information
        String showOpenUriDialogText = getModel().getUserPreference(MultiBitModel.OPEN_URI_SHOW_DIALOG);
        String useUriText = getModel().getUserPreference(MultiBitModel.OPEN_URI_USE_URI);

        if (Boolean.FALSE.toString().equalsIgnoreCase(useUriText)
                && Boolean.FALSE.toString().equalsIgnoreCase(showOpenUriDialogText)) {
            // ignore open URI request
            log.debug("Bitcoin URI ignored because useUriText = '" + useUriText + "', showOpenUriDialogText = '"
                    + showOpenUriDialogText + "'");
            org.multibit.message.Message message = new org.multibit.message.Message(super.getLocaliser().getString("showOpenUriView.paymentRequestIgnored"));
            MessageManager.INSTANCE.addMessage(message);
            
            return;
        }
        if (this.eventHandeler.rawBitcoinURI == null || this.eventHandeler.rawBitcoinURI.equals("")) {
            log.debug("No Bitcoin URI found to handle");
            // displayView(getCurrentView());
            return;
        }
        // Process the URI
        // TODO Consider handling the possible runtime exception at a suitable
        // level for recovery.

        // Early MultiBit versions did not URL encode the label hence may
        // have illegal embedded spaces - convert to ENCODED_SPACE_CHARACTER i.e
        // be lenient
        String uriString = this.eventHandeler.rawBitcoinURI.toString().replace(" ", ENCODED_SPACE_CHARACTER);
        BitcoinURI bitcoinURI = null;
        try {
            bitcoinURI = new BitcoinURI(this.getModel().getNetworkParameters(), uriString);
        } catch (BitcoinURIParseException pe) {
            log.error("Could not parse the uriString '" + uriString + "', aborting");
            return;
        }

        // Convert the URI data into suitably formatted view data.
        String address = bitcoinURI.getAddress().toString();
        String label = "";
        try {
            // No label? Set it to a blank String otherwise perform a URL decode
            // on it just to be sure.
            label = null == bitcoinURI.getLabel() ? "" : URLDecoder.decode(bitcoinURI.getLabel(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("Could not decode the label in UTF-8. Unusual URI entry or platform.");
        }
        // No amount? Set it to zero.
        BigInteger numericAmount = null == bitcoinURI.getAmount() ? BigInteger.ZERO : bitcoinURI.getAmount();
        String amount = getLocaliser().bitcoinValueToStringNotLocalised(numericAmount, false, false);

        if (Boolean.FALSE.toString().equalsIgnoreCase(showOpenUriDialogText)) {
            // Do not show confirm dialog - go straight to send view.
            // Populate the model with the URI data.
            getModel().setActiveWalletPreference(MultiBitModel.SEND_ADDRESS, address);
            getModel().setActiveWalletPreference(MultiBitModel.SEND_LABEL, label);
            getModel().setActiveWalletPreference(MultiBitModel.SEND_AMOUNT, amount);
            getModel().setActiveWalletPreference(MultiBitModel.SEND_PERFORM_PASTE_NOW, "true");
            log.debug("Routing straight to send view for address = " + address);

            getModel().setUserPreference(MultiBitModel.BRING_TO_FRONT, "true");
            displayView(View.SEND_BITCOIN_VIEW);
            return;
        } else {
            // Show the confirm dialog to see if the user wants to use URI.
            // Populate the model with the URI data.
            getModel().setUserPreference(MultiBitModel.OPEN_URI_ADDRESS, address);
            getModel().setUserPreference(MultiBitModel.OPEN_URI_LABEL, label);
            getModel().setUserPreference(MultiBitModel.OPEN_URI_AMOUNT, amount);
            log.debug("Routing to show open uri view for address = " + address);

            displayView(View.SHOW_OPEN_URI_DIALOG_VIEW);
            return;
        }
    }

    public PeerEventListener getPeerEventListener() {
        return peerEventListener;
    }

    @Override
    public final AbstractEventHandeler getEventHandeler() {
        return this.eventHandeler;
    }
    
    private class EventHandeler extends AbstractEventHandeler<MultiBitController> {

    /**
         * Multiple threads will write to this variable so require it to be
         * volatile to ensure that latest write is what gets read
     */
        private volatile URI rawBitcoinURI = null;

        public EventHandeler(MultiBitController coreController) {
            super(coreController);
    }

        @Override
        public void handleOpenURIEvent(URI rawBitcoinURI) {
            this.rawBitcoinURI = rawBitcoinURI;
            handleOpenURI();

        }

        @Override
        public void handleQuitEvent(ExitAction exitAction) {
            exitAction.setMultiBitController(super.controller);
        }
    }
}
