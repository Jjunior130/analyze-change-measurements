(ns analyze-change-measurements.view
  (:require [baking-soda.core :as b]
            [kee-frame.core :as kf]
            [markdown.core :refer [md->html]]
            [reagent.core :as r]
            [re-frame.core :as rf]))

; the navbar components are implemented via baking-soda [1]
; library that provides a ClojureScript interface for Reactstrap [2]
; Bootstrap 4 components.
; [1] https://github.com/gadfly361/baking-soda
; [2] http://reactstrap.github.io/

(defn nav-link [title page]
  [b/NavItem
   [b/NavLink
    {:href   (kf/path-for [page])
     :active (= page @(rf/subscribe [:nav/page]))}
    title]])

(defn navbar []
  (r/with-let [expanded? (r/atom true)]
              [b/Navbar {:light true
                         :class-name "navbar-dark bg-primary"
                         :expand "md"}
               [b/NavbarBrand {:href "/"} "analyze-change-measurements"]
               [b/NavbarToggler {:on-click #(swap! expanded? not)}]
               [b/Collapse {:is-open @expanded? :navbar true}
                [b/Nav {:class-name "mr-auto" :navbar true}
                 [nav-link "Home" :home]
                 [nav-link "About" :about]]]]))

(defn about-page []
  [:div.container
   [:div.row
    [:div.col-md-12
     [:img {:src "/img/warning_clojure.png"}]]]])

(defn home-page []
  [:div.container
   (when-let [docs @(rf/subscribe [:docs])]
     [:div.row>div.col-sm-12
      [:div {:dangerouslySetInnerHTML
             {:__html (md->html docs)}}]])])

(defn root-component []
  [:div
   [navbar]
   [kf/switch-route (fn [route] (get-in route [:data :name]))
    :home home-page
    :about about-page
    nil [:div ""]]])
