<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Huvudbok</h2>
  <p class="lead">Varje konto inleds med ingående balans och följs av periodens transaktioner med löpande saldo.</p>
  <ul class="summary">
    <#list summaryLines as line>
      <li>${line}</li>
    </#list>
  </ul>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
  <p class="note">Rader utan verifikationsnummer visar kontots ingående balans för urvalet.</p>
</@layout.page>
