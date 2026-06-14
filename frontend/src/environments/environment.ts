export const environment = {
  production: false,
  // Relative same-origin base — XHR goes through ng serve's proxy.conf.json to the backend so the
  // cad_session cookie (SameSite=Lax) and Angular XSRF work locally (research D10/FE-1/FE-2).
  apiBaseUrl: '/api'
};
