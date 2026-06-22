import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';
import { environment } from './environments/environment';
import { sanitizeApiBase } from './app/core/api-base';

// Backstop a build-mangled apiBaseUrl (e.g. Git Bash converting "/api" to "C:/Program Files/Git/api")
// BEFORE bootstrap, so every service/interceptor that reads environment.apiBaseUrl gets a safe value
// and the app can never issue a file:// API request. See sanitizeApiBase for the why.
environment.apiBaseUrl = sanitizeApiBase(environment.apiBaseUrl);

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
