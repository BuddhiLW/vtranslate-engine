(ns hive-mcp.addons.protocol)

(defprotocol IAddon
  (initialize! [this config]))
