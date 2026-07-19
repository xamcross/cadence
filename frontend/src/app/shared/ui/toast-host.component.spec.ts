import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ToastHostComponent } from './toast-host.component';
import { ToastService } from './toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

describe('ToastHostComponent', () => {
  let fixture: ComponentFixture<ToastHostComponent>;
  let el: HTMLElement;
  let svc: ToastService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ToastHostComponent] });
    svc = TestBed.inject(ToastService);
    fixture = TestBed.createComponent(ToastHostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders a toast pushed to the service', () => {
    svc.success('Saved'); fixture.detectChanges();
    const toast = el.querySelector('.toast');
    expect(toast?.textContent).toContain('Saved');
    expect(toast?.getAttribute('role')).toBe('status');
  });

  it('uses role=alert for error toasts', () => {
    svc.error('Boom'); fixture.detectChanges();
    expect(el.querySelector('.toast--error')?.getAttribute('role')).toBe('alert');
  });

  it('dismiss button removes the toast', () => {
    svc.success('Saved'); fixture.detectChanges();
    (el.querySelector('.toast__close') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.toast')).toBeNull();
  });

  it('has zero axe violations with a toast shown', async () => {
    svc.info('FYI'); fixture.detectChanges();
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
