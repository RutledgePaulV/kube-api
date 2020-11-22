(ns kube-api.term.ui
  (:import [javax.swing AbstractAction JMenuBar JMenu JFrame SwingUtilities UIManager]
           [com.jediterm.terminal TabbedTerminalWidget RequestOrigin]
           [com.jediterm.terminal.ui.settings DefaultTabbedSettingsProvider]
           [com.jediterm.terminal.ui TerminalPanelListener TerminalWidget]
           [java.util.function Function]
           [com.formdev.flatlaf FlatDarculaLaf]))


(defn create-action ^AbstractAction [title callback]
  (proxy [AbstractAction] [title]
    (actionPerformed [action] (callback action))))

(defn create-menu ^JMenu [^String title & actions]
  (let [menu (JMenu. title)]
    (doseq [^AbstractAction action actions] (.add menu action))
    menu))

(defn create-menu-bar ^JMenuBar [& menus]
  (let [menu-bar (JMenuBar.)]
    (doseq [^JMenu menu menus] (.add menu-bar menu))
    menu-bar))

(defn ->function [f]
  (reify Function (apply [this argument] (f argument))))

(defn resize [^JFrame frame ^TerminalWidget terminal]
  (SwingUtilities/invokeLater
    (fn [] (let [dimensions (.getPreferredSize terminal)]
             (set! (.-width dimensions)
                   (+ (.-width dimensions)
                      (- (.getWidth frame) (.getWidth (.getContentPane frame)))))
             (set! (.-height dimensions)
                   (+ (.-height dimensions)
                      (- (.getHeight frame) (.getHeight (.getContentPane frame)))))
             (.setSize frame dimensions)))))

(defonce init-look-and-feel
  (delay (UIManager/setLookAndFeel (FlatDarculaLaf.))))

(defn create-frame [construct-tty-session]
  (force init-look-and-feel)
  (let [open-session   (fn [^TerminalWidget container]
                         (when (.canOpenSession container)
                           (doto (.createTerminalSession container (construct-tty-session))
                             (.start))))
        tabbed-widget  (TabbedTerminalWidget. (DefaultTabbedSettingsProvider.) (->function open-session))
        file-menu      (create-menu-bar
                         (create-menu "File"
                           (create-action "New Session" (fn [action] (open-session tabbed-widget)))))
        frame          (doto (JFrame. "PodTerm")
                         (.setJMenuBar file-menu))
        _              (resize frame tabbed-widget)
        _              (.add (.getContentPane frame) "Center" (.getComponent tabbed-widget))
        _              (doto frame
                         (.pack)
                         (.setLocationByPlatform true)
                         (.setVisible true)
                         (.setResizable true))
        panel-listener (reify TerminalPanelListener
                         (onPanelResize [this dimension request-origin]
                           (when (= request-origin RequestOrigin/Remote)
                             (resize frame tabbed-widget))
                           (.pack frame))
                         (onSessionChanged [this new-session]
                           (.setTitle frame (.getSessionName new-session)))
                         (onTitleChanged [this new-title]
                           (.setTitle frame new-title)))
        _              (.setTerminalPanelListener tabbed-widget panel-listener)]
    (open-session tabbed-widget)))