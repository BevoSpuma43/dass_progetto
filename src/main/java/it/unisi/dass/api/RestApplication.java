package it.unisi.dass.api;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** Imposta il prefisso {@code /api} delle risorse Jakarta REST all'interno dell'applicazione DASS. */
@ApplicationPath("/api")
public class RestApplication extends Application {
}
