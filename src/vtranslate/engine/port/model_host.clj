(ns vtranslate.engine.port.model-host
  "Port for a process that holds models on a device and can be told to load or
   unload one: a speaches server, an in-process runtime. The residency owner
   (vtranslate.engine.residency) decides WHAT to load and unload; an adapter of
   this port only carries it out and reports what is resident.")

(defprotocol IModelHost
  (loaded [this]
    "=> (r/ok #{model-id ...}) the models resident right now
        | (r/err :error/model-host {...})")
  (load! [this model-id]
    "Make `model-id` resident. Loading one already resident is not an error.
     => (r/ok model-id) | (r/err :error/model-host {...})")
  (unload! [this model-id]
    "Free `model-id`. => (r/ok :unloaded)
                        | (r/ok :busy)    a decode holds it; nothing was freed
                        | (r/ok :absent)  it was not resident
                        | (r/err :error/model-host {...})"))
