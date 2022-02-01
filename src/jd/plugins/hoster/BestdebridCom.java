//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JLabel;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtPasswordField;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.gui.InputChangedCallbackInterface;
import org.jdownloader.plugins.accounts.AccountBuilderInterface;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.gui.swing.components.linkbutton.JLink;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "bestdebrid.com" }, urls = { "" })
public class BestdebridCom extends PluginForHost {
    private static final String          API_BASE            = "https://bestdebrid.com/api/v1";
    private static MultiHosterManagement mhm                 = new MultiHosterManagement("bestdebrid.com");
    private static final int             defaultMAXDOWNLOADS = -1;
    private static final int             defaultMAXCHUNKS    = 0;
    private static final boolean         defaultRESUME       = true;

    @SuppressWarnings("deprecation")
    public BestdebridCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://bestdebrid.com/plans");
    }

    @Override
    public String getAGBLink() {
        return "https://bestdebrid.com/";
    }

    private Browser prepBR(final Browser br) {
        br.setCookiesExclusive(true);
        br.getHeaders().put("User-Agent", "JDownloader");
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws PluginException {
        return AvailableStatus.UNCHECKABLE;
    }

    @Override
    public boolean canHandle(final DownloadLink downloadLink, final Account account) throws Exception {
        if (account == null) {
            /* without account its not possible to download the link */
            return false;
        } else {
            return true;
        }
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        /* handle premium should never be called */
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    private void handleDL(final Account account, final DownloadLink link) throws Exception {
        br.setFollowRedirects(true);
        String dllink = checkDirectLink(link, this.getHost() + "directlink");
        if (dllink == null) {
            /* 2019-08-27: Test-code regarding Free Account download which is only possible via website. */
            final boolean use_website_for_free_account_downloads = false;
            if (account.getType() == AccountType.FREE && use_website_for_free_account_downloads) {
                this.br.postPage("https://bestdebrid.com/api/v1/generateLink", "link=" + Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, this)) + "&pass=&boxlinklist=0");
                dllink = PluginJSonUtils.getJsonValue(br, "link");
                if (!StringUtils.isEmpty(dllink) && !dllink.startsWith("http") && !dllink.startsWith("/")) {
                    dllink = "https://" + this.getHost() + "/" + dllink;
                }
            } else {
                this.br.getPage(API_BASE + "/generateLink?auth=" + Encoding.urlEncode(account.getPass()) + "&link=" + Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, this)));
            }
            handleAPIErrors(this.br, account, link);
            dllink = PluginJSonUtils.getJsonValue(br, "link");
            if (StringUtils.isEmpty(dllink)) {
                mhm.handleErrorGeneric(account, link, "dllinknull", 50, 5 * 60 * 1000l);
            }
        }
        link.setProperty(this.getHost() + "directlink", dllink);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, defaultRESUME, defaultMAXCHUNKS);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            handleAPIErrors(this.br, account, link);
            mhm.handleErrorGeneric(account, link, "unknown_dl_error", 50, 5 * 60 * 1000l);
        }
        this.dl.startDownload();
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.MULTIHOST };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        this.prepBR(this.br);
        mhm.runCheck(account, link);
        handleDL(account, link);
    }

    private String checkDirectLink(final DownloadLink link, final String property) {
        String dllink = link.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openHeadConnection(dllink);
                if (this.looksLikeDownloadableContent(con)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    return dllink;
                } else {
                    throw new IOException();
                }
            } catch (final Exception e) {
                logger.log(e);
                return null;
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        return null;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        this.prepBR(this.br);
        final AccountInfo ai = new AccountInfo();
        loginAPI(account);
        /*
         * 2019-07-30: This means that an account is owned by a reseller. Reseller accounts have no limits (no daily bandwidth/numberof
         * links limits even compared to premium).
         */
        final Map<String, Object> user = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final Boolean bypass_api_limit = (Boolean) user.get("bypass_api_limit");
        final String expireStr = (String) user.get("expire");
        /* 2019-07-30: E.g. "premium":true --> Website may even show a more exact status e.g. "Debrid Plan : Silver" */
        // final String premium = PluginJSonUtils.getJson(br, "premium");
        /* 2019-07-30: credit value = for resellers --> Money on the account which can be used to 'buy more links'. */
        final String creditStr = user.get("credit").toString();
        int credit = 0;
        long validuntil = 0;
        if (expireStr != null) {
            validuntil = TimeFormatter.getMilliSeconds(expireStr, "yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        }
        if (creditStr != null && creditStr.matches("\\d+")) {
            credit = Integer.parseInt(creditStr);
        }
        String statusAcc;
        if (validuntil < System.currentTimeMillis()) {
            account.setType(AccountType.FREE);
            statusAcc = "Free Account";
            if (bypass_api_limit == Boolean.TRUE) {
                statusAcc += " [Reseller]";
                ai.setUnlimitedTraffic();
            } else {
                /*
                 * 2019-07-12: Seems like they have hosts which free account users can download from but their hostlist does not state which
                 * ones these are. In the "Informations" tab there is a list of "Free hosters": https://bestdebrid.com/downloader (more
                 * precise: https://bestdebrid.com/info.php) For now, we will simply set the free account traffic to ZERO.
                 */
                // ai.setTrafficLeft(0);
                ai.setUnlimitedTraffic();
                /*
                 * 2019-08-37: Free-Account Downloads via API are not possible.
                 */
                ai.setTrafficLeft(0);
                account.setMaxSimultanDownloads(0);
                statusAcc += " [Downloads are only possible via browser]";
            }
        } else {
            account.setType(AccountType.PREMIUM);
            statusAcc = "Premium Account";
            if (bypass_api_limit == Boolean.TRUE) {
                statusAcc += " [Reseller]";
            }
            ai.setUnlimitedTraffic();
            account.setMaxSimultanDownloads(defaultMAXDOWNLOADS);
            ai.setValidUntil(validuntil, this.br);
        }
        if (credit > 0) {
            statusAcc += " [" + credit + " credits]";
        }
        ai.setStatus(statusAcc);
        br.getPage(API_BASE + "/hosts?auth=" + Encoding.urlEncode(account.getPass()));
        final ArrayList<String> supportedhostslist = new ArrayList<String>();
        LinkedHashMap<String, Object> entries;
        final ArrayList<Object> hosters;
        final Object hostersO = JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        int counter = 0;
        if (hostersO instanceof LinkedHashMap) {
            /* 2019-07-15: They are using a map with numbers as String as key --> This is a workaround for that */
            entries = (LinkedHashMap<String, Object>) hostersO;
            hosters = new ArrayList<Object>();
            Object tempO = null;
            do {
                if (counter == 0) {
                    /* 2019-07-15: Special case: Array might start with 1 so check here for Object on [0] */
                    tempO = entries.get("0");
                    if (tempO != null) {
                        hosters.add(tempO);
                    }
                    counter++;
                }
                tempO = entries.get(Integer.toString(counter));
                if (tempO != null) {
                    hosters.add(tempO);
                }
                counter++;
            } while (tempO != null && counter > 1);
        } else {
            /* 2019-07-15: In case they ever correct their Map to an Array, we will need the following line. */
            hosters = (ArrayList<Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        }
        for (final Object hosterO : hosters) {
            entries = (LinkedHashMap<String, Object>) hosterO;
            // final String downsincedate = (String)entries.get("downsincedate");
            final String status = (String) entries.get("status");
            /* 2019-07-12: TLDs are missing - admin has been advised to change this! */
            String host = (String) entries.get("name");
            if (!StringUtils.isEmpty(host) && "up".equalsIgnoreCase(status)) {
                /* 2019-08-27: Some workarounds for some bad given hostnames without TLD */
                if (host.equalsIgnoreCase("free")) {
                    host = "dl.free.fr";
                } else if (host.equalsIgnoreCase("load")) {
                    host = "load.to";
                }
                supportedhostslist.add(host);
            }
        }
        ai.setMultiHostSupport(this, supportedhostslist);
        account.setConcurrentUsePossible(true);
        return ai;
    }

    private void loginAPI(final Account account) throws IOException, PluginException {
        synchronized (account) {
            br.setFollowRedirects(true);
            account.setPass(correctPassword(account.getPass()));
            if (!isAPIKey(account.getPass())) {
                throw new AccountInvalidException("Invalid API key format");
            }
            br.getPage(API_BASE + "/user?auth=" + Encoding.urlEncode(account.getPass()));
            /** 2019-07-05: No idea how long this token is valid! */
            final String status = PluginJSonUtils.getJson(br, "error");
            if (status != null && !"0".equals(status)) {
                if (account.getLastValidTimestamp() <= -1) {
                    /*
                     * This account is checked for the first time --> Show more detailed error message to let user know his potential
                     * mistake of entering username & password instead of API Key (e.g. Headless/myjd) users.
                     */
                    final String jdLoginFailedText;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        jdLoginFailedText = "Ungültiger API Key?\r\nGib hier NICHT deinen Benutzername & Passwort ein!\r\nGib deinen API Key in beide Felder ein!\r\nDiesen findest du hier: bestdebrid.com/profile.php";
                    } else {
                        jdLoginFailedText = "Invalid API Key?\r\nDo NOT enter your username & password here!\r\nEnter your API Key in both fields!\r\nYou will find your API Key here: bestdebrid.com/profile.php";
                    }
                    throw new AccountInvalidException(jdLoginFailedText);
                } else {
                    /* E.g. {"error":"bad username OR bad password"} */
                    final String fail_reason = PluginJSonUtils.getJson(br, "message");
                    if (!StringUtils.isEmpty(fail_reason)) {
                        throw new AccountInvalidException("Reason: " + fail_reason);
                    } else {
                        throw new AccountInvalidException();
                    }
                }
            }
            /*
             * Used logs in via apikey - via website, username & email are required. Set mail as username so: 1. User can identify different
             * accounts in JD better and 2. If someone steals the users' database he still cannot login via website!
             */
            final String email = PluginJSonUtils.getJson(br, "email");
            // final String username = PluginJSonUtils.getJson(br, "username");
            if (!StringUtils.isEmpty(email)) {
                account.setUser(email);
            }
        }
    }

    private static boolean isAPIKey(final String str) {
        if (str == null) {
            return false;
        } else if (str.matches("[a-zA-Z0-9_/\\+\\=\\-]+")) {
            /* Very simple check for base64 Strings. */
            return true;
        } else {
            return false;
        }
    }

    private static String correctPassword(final String pw) {
        return pw.trim();
    }

    private void handleAPIErrors(final Browser br, final Account account, final DownloadLink link) throws PluginException, InterruptedException {
        final String status = PluginJSonUtils.getJson(br, "error");
        final String errorStr = PluginJSonUtils.getJson(br, "message");
        if (status != null && !"0".equals(status)) {
            if (errorStr != null) {
                if (errorStr.equalsIgnoreCase("Bad token (expired, invalid)")) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else if (errorStr.equalsIgnoreCase("You need to be Premium to use our API.")) {
                    /* 2019-08-27: Happens when you try to download via API via free account */
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, errorStr, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                }
            }
            mhm.handleErrorGeneric(account, link, "generic_api_error", 50, 5 * 60 * 1000l);
        }
    }

    @Override
    public AccountBuilderInterface getAccountFactory(InputChangedCallbackInterface callback) {
        return new BestdebridAccountFactory(callback);
    }

    public static class BestdebridAccountFactory extends MigPanel implements AccountBuilderInterface {
        /**
         *
         */
        private static final long serialVersionUID = 1L;
        private final String      PINHELP          = "Enter your Apikey";
        private final JLabel      apikeyLabel;

        private String getPassword() {
            if (this.pass == null) {
                return null;
            } else if (EMPTYPW.equals(new String(this.pass.getPassword()))) {
                return null;
            } else {
                return BestdebridCom.correctPassword(new String(this.pass.getPassword()));
            }
        }

        public boolean updateAccount(Account input, Account output) {
            boolean changed = false;
            if (!StringUtils.equals(input.getUser(), output.getUser())) {
                output.setUser(input.getUser());
                changed = true;
            }
            if (!StringUtils.equals(input.getPass(), output.getPass())) {
                output.setPass(input.getPass());
                changed = true;
            }
            return changed;
        }

        private final ExtPasswordField pass;
        private static String          EMPTYPW = "                 ";

        public BestdebridAccountFactory(final InputChangedCallbackInterface callback) {
            super("ins 0, wrap 2", "[][grow,fill]", "");
            add(new JLabel("Click here to find your API Key:"));
            add(new JLink("https://bestdebrid.com/profile.php"));
            add(apikeyLabel = new JLabel("API Key:"));
            add(this.pass = new ExtPasswordField() {
                @Override
                public void onChanged() {
                    callback.onChangedInput(this);
                }
            }, "");
            pass.setHelpText(PINHELP);
        }

        @Override
        public JComponent getComponent() {
            return this;
        }

        @Override
        public void setAccount(Account defaultAccount) {
            if (defaultAccount != null) {
                // name.setText(defaultAccount.getUser());
                pass.setText(defaultAccount.getPass());
            }
        }

        @Override
        public boolean validateInputs() {
            final String pw = getPassword();
            if (BestdebridCom.isAPIKey(pw)) {
                apikeyLabel.setForeground(Color.BLACK);
                return true;
            } else {
                apikeyLabel.setForeground(Color.RED);
                return false;
            }
        }

        @Override
        public Account getAccount() {
            return new Account(null, getPassword());
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}