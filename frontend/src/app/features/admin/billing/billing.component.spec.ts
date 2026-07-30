import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BillingComponent } from './billing.component';
import { BillingService, EntitlementView } from './billing.service';
import { ToastService } from '../../../shared/ui/toast.service';

const FREE: EntitlementView = { plan: 'FREE', status: null, expiresAt: null, boundAt: null };
const TEAM: EntitlementView = { plan: 'TEAM', status: 'ACTIVE', expiresAt: '2027-01-15T10:30:00Z', boundAt: '2026-07-30T00:00:00Z' };

describe('BillingComponent (032 US1/US4)', () => {
  let billing: jasmine.SpyObj<BillingService>;
  let toast: jasmine.SpyObj<ToastService>;

  function create(queryParams: Record<string, string> = {}): ComponentFixture<BillingComponent> {
    TestBed.configureTestingModule({
      imports: [BillingComponent],
      providers: [
        { provide: BillingService, useValue: billing },
        { provide: ToastService, useValue: toast },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
      ]
    });
    const fixture = TestBed.createComponent(BillingComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    billing = jasmine.createSpyObj<BillingService>('BillingService', ['getEntitlement', 'createCheckoutSession', 'claim']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    billing.getEntitlement.and.returnValue(of(FREE));
  });

  it('shows the Free plan card with an upgrade action', () => {
    const el: HTMLElement = create().nativeElement;
    expect(el.textContent).toContain('Free');
    expect(el.querySelector('[data-test=upgrade]')).toBeTruthy();
  });

  it('upgrade fetches a checkout session and redirects externally', () => {
    billing.createCheckoutSession.and.returnValue(of({ checkoutUrl: 'https://checkout.example/x' }));
    const fixture = create();
    spyOn(fixture.componentInstance, 'navigateExternal');
    (fixture.nativeElement.querySelector('[data-test=upgrade]') as HTMLButtonElement).click();
    expect(fixture.componentInstance.navigateExternal).toHaveBeenCalledWith('https://checkout.example/x');
  });

  it('claims the license from the return query param and toasts success', () => {
    billing.claim.and.returnValue(of(TEAM));
    billing.getEntitlement.and.returnValue(of(TEAM));
    create({ license_id: 'L1' });
    expect(billing.claim).toHaveBeenCalledWith('L1');
    expect(toast.success).toHaveBeenCalled();
  });

  it('shows the typed claim error inline on refusal', () => {
    billing.claim.and.returnValue(throwError(() => ({ status: 409, error: { error: 'license_already_bound' } })));
    const fixture = create({ license_id: 'L1' });
    expect(fixture.componentInstance.error()).toBeTruthy();
  });

  it('on TEAM shows status and the customer-portal link instead of upgrade', () => {
    billing.getEntitlement.and.returnValue(of(TEAM));
    const el: HTMLElement = create().nativeElement;
    expect(el.querySelector('[data-test=upgrade]')).toBeFalsy();
    expect(el.querySelector('[data-test=portal-link]')).toBeTruthy();
  });
});
